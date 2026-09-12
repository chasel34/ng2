package com.chasel.ng2n.data.filters

import com.chasel.ng2n.core.api.BlockWordList
import com.chasel.ng2n.core.api.BlockedUser
import com.chasel.ng2n.core.api.EMPTY_BLOCK_WORDS
import com.chasel.ng2n.core.api.fetchBlockWords
import com.chasel.ng2n.core.api.officialFilterRules
import com.chasel.ng2n.core.api.setBlockWords
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.FilterRuleOrigin
import com.chasel.ng2n.core.local.createFilterRule
import com.chasel.ng2n.core.local.removeFilterRule
import com.chasel.ng2n.core.local.upsertFilterRule
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.chasel.ng2n.data.settings.FilterRule as StoredRule
import com.chasel.ng2n.data.settings.FilterRuleKind as StoredKind
import com.chasel.ng2n.data.settings.FilterRuleOrigin as StoredOrigin

/**
 * 屏蔽规则的两份数据源 —— 直译 RN 侧 `src/store/filters.ts`。
 *
 * - **本地规则**:DataStore 持久化(票 14 的 `SettingsStore.localFilterRules`,
 *   MMKV key `filters/local-rules` 的对应物),**游客也能用**,卸载即丢;
 * - **官方屏蔽词**:NGA 账号云端那份(API 文档 §11.5),按账号分桶,整表覆盖写。
 *
 * 判定统一走 `core/local` 的 `matchFilterRules` —— 两份规则在 [allRules] 里拼成一张表,
 * **本地在前**:折叠行报的是用户自己加的那条,点「解除」才落在他能删的规则上。
 *
 * ## 两个 FilterRule 的桥
 *
 * 票 10 在 `core/local/Filters.kt` 落了一份**判定用**的 `FilterRule`(kind/origin 是枚举),
 * 票 14 在 `data/settings/FilterRules.kt` 落了一份**存储用**的(kind/origin 是字符串,
 * `@Serializable`)。两份是各自票的独立产物,这里不去合并它们(票外重构),
 * 只在读写 DataStore 的那一刻做映射:[StoredRule.toCore] / [FilterRule.toStored]。
 *
 * **校验与建规则一律走 core 那一份**([com.chasel.ng2n.core.local.validateFilterRule]):
 * 只有它带 P3-05 的资源上限(长度上限 + 嵌套量词粗检)。`data/settings` 里那份同名函数
 * 没有这些闸,本路径不碰它。
 */
@Singleton
class FilterRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
  private val accounts: AccountStore,
) {

  // ---------------------------------------------------------------- 本地规则

  /** 本地规则表(判定用形态)。坏条目在票 14 的 `sanitizeFilterRules` 里已经滤过一遍。 */
  val localRules: Flow<List<FilterRule>> =
    settings.localFilterRules.map { stored -> stored.mapNotNull(StoredRule::toCore) }

  /**
   * 加一条本地规则,返回它(调用方要拿它写「已添加…」并给「撤销」)。
   *
   * 调用前先过 `validateFilterRule` —— 与 RN 版同一条边界:对话框负责把话说清楚,
   * 这里不再校验。
   */
  suspend fun addLocal(input: FilterRuleInput, nowSeconds: Long = nowSeconds()): FilterRule {
    val rule = createFilterRule(input, nowSeconds)
    settings.updateFilterRules { current ->
      upsertFilterRule(current.mapNotNull(StoredRule::toCore), rule).map(FilterRule::toStored)
    }
    return rule
  }

  suspend fun removeLocal(id: String) {
    settings.updateFilterRules { current ->
      removeFilterRule(current.mapNotNull(StoredRule::toCore), id).map(FilterRule::toStored)
    }
  }

  /** 「撤销」用:原样放回去(id 不变,所以不会和自己重复)。 */
  suspend fun restoreLocal(rule: FilterRule) {
    settings.updateFilterRules { current ->
      upsertFilterRule(current.mapNotNull(StoredRule::toCore), rule).map(FilterRule::toStored)
    }
  }

  /** 楼层菜单「屏蔽此人」:加一条本地用户规则(RN 侧 `blockUserLocally`)。 */
  suspend fun blockUserLocally(name: String, uid: Long?): FilterRule =
    addLocal(FilterRuleInput(kind = FilterRuleKind.USER, value = name, uid = uid))

  // ---------------------------------------------------------------- 官方屏蔽词

  /**
   * 云端屏蔽表的取数状态。
   *
   * [uid] 记的是这张表属于谁 —— 切号之后不能拿上一个号的表充数(RN 侧靠
   * `queryKey: ['block-words', uid]` 分桶,这里靠这个字段判)。
   */
  data class BlockWordsState(
    val uid: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** `null` = 还没读到。**整表覆盖的接口 + 没读到的表 = 一次静默清空**,所以写之前必判 */
    val list: BlockWordList? = null,
    val error: Throwable? = null,
    val fetchedAtMs: Long = 0,
  )

  private val blockWordsState = MutableStateFlow(BlockWordsState())
  val blockWords: StateFlow<BlockWordsState> = blockWordsState.asStateFlow()

  private val blockWordsLock = Mutex()

  /**
   * 参与判定的完整规则表:本地在前、官方在后。
   *
   * 官方那份没拉回来(游客 / 还在飞)时就只有本地规则,不阻塞列表渲染 ——
   * 云端表回来后这条流会再发一次,那时命中的行再藏起来。
   */
  val allRules: Flow<List<FilterRule>> = combine(localRules, blockWords) { local, cloud ->
    local + officialFilterRules(cloud.list ?: EMPTY_BLOCK_WORDS)
  }

  /**
   * 当前账号 uid;游客是 `null`(接口要登录,游客一律不发请求)。
   *
   * **uid 一律由本仓库自己从 [AccountStore] 现读**,不让屏幕传进来:屏幕上那份是
   * `collectAsStateWithLifecycle(initialValue = null)`,进屏第一帧必然是 `null`,
   * 传进来就会把已经拉到的云端表当成「切到游客了」清掉,5 分钟的 staleTime 白搭。
   */
  val currentUid: Flow<String?> = accounts.currentUid

  /**
   * 进屏时调。**幂等**:同一个账号的表在 [STALE_MS] 内不重问
   * (RN 侧 `staleTime: 5 * 60 * 1000`,ADR-0002「能少打就少打」)。
   * 换了账号一律重拉。
   */
  suspend fun ensureBlockWords(nowMs: Long = System.currentTimeMillis()) {
    val uid = accounts.currentUid.first()
    if (uid == null) {
      // 游客:清掉上一个号留下的表,别让它继续参与判定
      blockWordsState.value = BlockWordsState()
      return
    }
    val current = blockWordsState.value
    if (current.uid == uid && current.list != null && nowMs - current.fetchedAtMs < STALE_MS) return
    load(uid, refreshing = false, nowMs = nowMs)
  }

  /** 下拉刷新:用户可能刚在网页版改过,给一个重读的口子。游客态是 no-op。 */
  suspend fun refreshBlockWords(nowMs: Long = System.currentTimeMillis()) {
    val uid = accounts.currentUid.first() ?: return
    load(uid, refreshing = true, nowMs = nowMs)
  }

  // 网络切 IO(票 35):调用方是主线程上的 `LaunchedEffect` / `viewModelScope`
  private suspend fun load(uid: String, refreshing: Boolean, nowMs: Long) = withContext(Dispatchers.IO) {
    blockWordsLock.withLock {
      val sameUid = blockWordsState.value.uid == uid
      blockWordsState.value = blockWordsState.value.copy(
        uid = uid,
        loading = !refreshing,
        refreshing = refreshing,
        // 换号时先把上一个号的表摘掉,免得新号的屏上闪一下别人的屏蔽词
        list = if (sameUid) blockWordsState.value.list else null,
        error = null,
      )
      try {
        val list = fetchBlockWords(client, uid)
        blockWordsState.value = blockWordsState.value.copy(
          loading = false,
          refreshing = false,
          list = list,
          error = null,
          fetchedAtMs = nowMs,
        )
      } catch (cancelled: CancellationException) {
        blockWordsState.value = blockWordsState.value.copy(loading = false, refreshing = false)
        throw cancelled
      } catch (error: Throwable) {
        blockWordsState.value = blockWordsState.value.copy(
          loading = false,
          refreshing = false,
          error = error,
        )
      }
    }
  }

  /**
   * 官方屏蔽表的写操作。接口只有「整表覆盖」(API 文档 §11.5),所以每次写都是
   * 拿当前这张表改一条再写回去。
   *
   * **表还没拉回来时不许写** —— 那会拿一张空表覆盖掉云端的全部屏蔽词。
   * 乐观更新 + 失败回滚:开关点了就该立刻动,失败把服务端那句话交给调用方去说
   * (`failureText`)。
   */
  private suspend fun edit(change: (BlockWordList) -> BlockWordList) {
    val uid = accounts.currentUid.first() ?: throw IllegalStateException("登录后才能同步官方屏蔽词")
    val before = blockWordsState.value
    val current = before.list ?: throw IllegalStateException("官方屏蔽表还没读到,下拉刷新后再试")
    val next = change(current)

    blockWordsState.value = before.copy(list = next, error = null)
    withContext(Dispatchers.IO) {
      try {
        setBlockWords(client, uid, next)
      } catch (error: Throwable) {
        // 回滚。只还原表本身:期间可能已经有一次重拉把 fetchedAt 推进了
        blockWordsState.value = blockWordsState.value.copy(list = before.list)
        throw error
      }
    }
  }

  suspend fun addOfficialWord(word: String) = edit { list ->
    list.copy(words = listOf(word) + list.words.filter { it != word })
  }

  suspend fun removeOfficialWord(word: String) = edit { list ->
    list.copy(words = list.words.filter { it != word })
  }

  suspend fun removeOfficialUser(user: BlockedUser) = edit { list ->
    list.copy(
      users = list.users.filter { item ->
        if (user.uid == null) item.name != user.name else item.uid != user.uid
      },
    )
  }

  /** 整表写回。「撤销」把改动前那张表原样写回去即可。 */
  suspend fun replaceOfficial(replacement: BlockWordList) = edit { replacement }

  private companion object {
    /** RN 侧 `staleTime: 5 * 60 * 1000`。 */
    const val STALE_MS = 5 * 60 * 1000L
  }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/**
 * 存储形态 → 判定形态。认不出 kind/origin 的条目**跳过**(返回 `null`),
 * 与票 14 `sanitizeFilterRules` 同一条口径:一条脏数据不该把整张表拖垮。
 */
fun StoredRule.toCore(): FilterRule? {
  val kind = when (kindEnum) {
    StoredKind.USER -> FilterRuleKind.USER
    StoredKind.KEYWORD -> FilterRuleKind.KEYWORD
    StoredKind.CATEGORY -> FilterRuleKind.CATEGORY
    null -> return null
  }
  val origin = when (originEnum) {
    StoredOrigin.LOCAL -> FilterRuleOrigin.LOCAL
    StoredOrigin.OFFICIAL -> FilterRuleOrigin.OFFICIAL
    // origin 是后加的字段,老存档里可能没有 —— 本地表里存的本来就只有本地规则
    null -> FilterRuleOrigin.LOCAL
  }
  return FilterRule(
    id = id,
    kind = kind,
    origin = origin,
    value = value,
    regex = regex,
    uid = uid,
    createdAt = createdAt,
  )
}

/** 判定形态 → 存储形态。 */
fun FilterRule.toStored(): StoredRule = StoredRule(
  id = id,
  kind = kind.wire,
  origin = origin.wire,
  value = value,
  regex = regex,
  uid = uid,
  createdAt = createdAt,
)
