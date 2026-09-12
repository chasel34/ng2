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

@Singleton
class FilterRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
  private val accounts: AccountStore,
) {

  val localRules: Flow<List<FilterRule>> =
    settings.localFilterRules.map { stored -> stored.mapNotNull(StoredRule::toCore) }

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

  suspend fun restoreLocal(rule: FilterRule) {
    settings.updateFilterRules { current ->
      upsertFilterRule(current.mapNotNull(StoredRule::toCore), rule).map(FilterRule::toStored)
    }
  }

  suspend fun blockUserLocally(name: String, uid: Long?): FilterRule =
    addLocal(FilterRuleInput(kind = FilterRuleKind.USER, value = name, uid = uid))

  data class BlockWordsState(
    val uid: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val list: BlockWordList? = null,
    val error: Throwable? = null,
    val fetchedAtMs: Long = 0,
  )

  private val blockWordsState = MutableStateFlow(BlockWordsState())
  val blockWords: StateFlow<BlockWordsState> = blockWordsState.asStateFlow()

  private val blockWordsLock = Mutex()

  val allRules: Flow<List<FilterRule>> = combine(localRules, blockWords) { local, cloud ->
    local + officialFilterRules(cloud.list ?: EMPTY_BLOCK_WORDS)
  }

  val currentUid: Flow<String?> = accounts.currentUid

  suspend fun ensureBlockWords(nowMs: Long = System.currentTimeMillis()) {
    val uid = accounts.currentUid.first()
    if (uid == null) {
      blockWordsState.value = BlockWordsState()
      return
    }
    val current = blockWordsState.value
    if (current.uid == uid && current.list != null && nowMs - current.fetchedAtMs < STALE_MS) return
    load(uid, refreshing = false, nowMs = nowMs)
  }

  suspend fun refreshBlockWords(nowMs: Long = System.currentTimeMillis()) {
    val uid = accounts.currentUid.first() ?: return
    load(uid, refreshing = true, nowMs = nowMs)
  }

  private suspend fun load(uid: String, refreshing: Boolean, nowMs: Long) = withContext(Dispatchers.IO) {
    blockWordsLock.withLock {
      val sameUid = blockWordsState.value.uid == uid
      blockWordsState.value = blockWordsState.value.copy(
        uid = uid,
        loading = !refreshing,
        refreshing = refreshing,
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

  suspend fun replaceOfficial(replacement: BlockWordList) = edit { replacement }

  private companion object {
    const val STALE_MS = 5 * 60 * 1000L
  }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

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

fun FilterRule.toStored(): StoredRule = StoredRule(
  id = id,
  kind = kind.wire,
  origin = origin.wire,
  value = value,
  regex = regex,
  uid = uid,
  createdAt = createdAt,
)
