package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.api.fetchTopicList
import com.chasel.ng2n.core.api.mergeTopicPages
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个版块的主题列表,35 条一页往下翻。直译 RN 侧 `store/topic-list.ts`
 * (TanStack `useInfiniteQuery` 的那一份语义)。
 *
 * ## 为什么是进程级仓库而不是 ViewModel
 *
 * RN 侧这份数据住在全局 query cache 里:从版块页进主题、返回,列表与滚动位置都还在;
 * 子版块屏(`sub-boards`)更是**直接读版块页已经拉过的第一页**(ADR-0002:能少打就少打),
 * 靠的就是「同一个 key 命中同一份缓存」。所以这里也做成进程级、按 key 分桶。
 * 桶数有上限([MAX_ENTRIES]),满了按最久没碰过的丢 —— 对应 TanStack 的 gcTime。
 *
 * ## 排序 / 精华区进 key
 *
 * 换排序等于换一份数据,不能把两种顺序的页混在一起;精华区(`recommend=1`)与普通
 * 列表也是两份数据,不能混页。
 */
@Singleton
class TopicListRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(
    val boardId: Long,
    val kind: BoardKind,
    val sort: TopicSort,
    /** 精华区:`recommend=1`,服务端固定 `order_by=postdatedesc`,[sort] 不生效 */
    val recommend: Boolean = false,
  )

  data class State(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    /** 已按 tid 去重的拼页结果(置顶主题每页都会再回来一次) */
    val topics: List<Topic> = emptyList(),
    val error: Throwable? = null,
    val loadingNextPage: Boolean = false,
    val refreshing: Boolean = false,
    val hasNextPage: Boolean = true,
  )

  private val entries = MutableStateFlow<Map<Key, State>>(emptyMap())
  val states: StateFlow<Map<Key, State>> = entries.asStateFlow()

  private val touched = LinkedHashMap<Key, Long>()
  private val locks = HashMap<Key, Mutex>()

  fun stateOf(key: Key): State = entries.value[key] ?: State()

  @Synchronized
  private fun lockOf(key: Key): Mutex = locks.getOrPut(key) { Mutex() }

  private fun put(key: Key, transform: (State) -> State) {
    val current = entries.value[key] ?: State()
    var next = entries.value + (key to transform(current))
    touched[key] = System.nanoTime()
    // 桶满了丢最久没碰过的那条(对应 TanStack 的 gcTime)
    if (next.size > MAX_ENTRIES) {
      val victim = touched.entries.filter { it.key in next.keys }.minByOrNull { it.value }?.key
      if (victim != null && victim != key) {
        next = next - victim
        touched.remove(victim)
        synchronized(this) { locks.remove(victim) }
      }
    }
    entries.value = next
  }

  /** 新的列表页面需要最新数据；返回已有页面则保留分页与阅读位置。 */
  suspend fun loadOnEntry(key: Key, restored: Boolean) {
    if (restored) ensureFirstPage(key) else refresh(key)
  }

  /** 恢复已有列表或读取版块元信息时调：有第一页就复用。 */
  suspend fun ensureFirstPage(key: Key) {
    if (entries.value[key]?.pages?.isNotEmpty() == true) {
      // 只是把它挪到 LRU 的近端
      put(key) { it }
      return
    }
    loadFirstPage(key)
  }

  private suspend fun loadFirstPage(key: Key) = lockOf(key).withLock {
    if (entries.value[key]?.pages?.isNotEmpty() == true) return@withLock
    put(key) { it.copy(loading = true, error = null) }
    fetchInto(key, page = 1, replace = true)
  }

  /**
   * 下拉刷新:**先把已翻的页砍到只剩第一页,再重取**。
   *
   * 直接全量重取会把加载过的每一页都重打一遍 —— 翻到第 10 页时下拉一次就是 10 个
   * `thread.php`,正好撞在 NGA 封第三方客户端的枪口上(ADR-0002)。而且主题列表按
   * 最后回复排序,刷新本来就该回到第一页:后面那些页早就错位了。
   */
  suspend fun refresh(key: Key) = lockOf(key).withLock {
    put(key) { it.copy(refreshing = true, error = null) }
    fetchInto(key, page = 1, replace = true)
    put(key) { it.copy(refreshing = false) }
  }

  /**
   * 「重试」:比下拉刷新更狠的一档,给空态/错误态那两个按钮用。
   *
   * 除了重新请求,还要**忘掉 `thread.php` 上次试通的格式 × 域名组合**
   * (2026-08-13「版块全空」排查):用户按这个按钮的时候,恰恰是「拿回来的东西不对」
   * 的时候,而反封锁链会优先复用上次成功的组合 —— 不清掉的话按一百次也还是从
   * 同一个坏组合开局。
   *
   * ⚠️ `thread.php` 这一条是版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 / 某人的主题
   * **共用**的,清它等于让这半个 app 一起重新试探。RN 版就是这个口径。
   */
  suspend fun retry(key: Key) = lockOf(key).withLock {
    client.forgetSuccessfulCombo("thread.php")
    put(key) { State(loading = true) }
    fetchInto(key, page = 1, replace = true)
  }

  /** 无限滚动的下一页。到底了 / 正在翻 / 上一次失败时都不发。 */
  suspend fun loadNextPage(key: Key) {
    val current = stateOf(key)
    if (!current.hasNextPage || current.loadingNextPage || current.loading) return
    lockOf(key).withLock {
      val now = stateOf(key)
      if (!now.hasNextPage || now.loadingNextPage) return@withLock
      put(key) { it.copy(loadingNextPage = true, error = null) }
      fetchInto(key, page = now.pages.size + 1, replace = false)
      put(key) { it.copy(loadingNextPage = false) }
    }
  }

  private suspend fun fetchInto(key: Key, page: Int, replace: Boolean) = withContext(Dispatchers.IO) {
    try {
      val fetched = fetchTopicList(
        client = client,
        boardId = key.boardId,
        kind = key.kind,
        page = page,
        sort = key.sort,
        recommend = key.recommend,
      )
      put(key) { state ->
        // 刷新是「整份换成新拉到的第一页」,但版块元信息不跟着一起换掉(票 36)
        val page = if (replace) keepBoardMeta(state.pages.firstOrNull(), fetched) else fetched
        val pages = if (replace) listOf(page) else state.pages + page
        state.copy(
          loading = false,
          pages = pages,
          topics = mergeTopicPages(pages),
          error = null,
          hasNextPage = hasNextPage(pages),
        )
      }
    } catch (cancelled: CancellationException) {
      put(key) { it.copy(loading = false, loadingNextPage = false, refreshing = false) }
      throw cancelled
    } catch (error: Throwable) {
      put(key) { it.copy(loading = false, error = error) }
    }
  }

  private companion object {
    const val MAX_ENTRIES = 8

    /**
     * 还有没有下一页(RN 侧 `getNextPageParam`):
     * 空页 = 到底了(或者被封了),再往下翻只会一直打同一个空响应。
     */
    fun hasNextPage(pages: List<TopicList>): Boolean {
      val last = pages.lastOrNull() ?: return true
      if (last.topics.isEmpty()) return false
      return pages.size + 1 <= last.totalPages
    }
  }
}

/**
 * 刷新时把上一份第一页的**版块元信息**带过来(票 36)。
 *
 * 版头(`__F.topped_topic`)与子版块(`__F.sub_forums`)都挂在 `__F` 上,同生共死:
 * 刷新那一发要是没带 `__F`(合集 fid < 0 在登录态下的行为、`thread.php` 六个业务
 * 共用一槽轮换到别的组合……现场没能坐实是哪一种),`refresh` 用新页整份替换旧页之后
 * 版头行与子版块 chip 行会一起消失,而 `ensureFirstPage` 看见 `pages` 非空就不再拉,
 * **坏状态一直留到进程重启**——退出重进都救不回来。
 *
 * 口径与分类树那边的 `mergeBoardTree` 一条:**新的有就用新的,新的没有才留旧的**。
 * 主题列表本身(`__T`)不参与合并,它就该以服务端这一发为准。
 */
internal fun keepBoardMeta(previous: TopicList?, fresh: TopicList): TopicList {
  if (previous == null) return fresh
  return fresh.copy(
    board = keepBoardFields(previous.board, fresh.board),
    // 子版块整块缺席才回落。服务端真把子版块下线时会连着 `__F` 一起给出新的 `sub_forums`,
    // 那一份是空对象也照样是「新的有」——但解析出来同样是空列表,两者在这一层分不开。
    // 分不开时选「留旧的」:子版块下线是罕事,`__F` 缺席是本票的现场。
    subBoards = fresh.subBoards.ifEmpty { previous.subBoards },
  )
}

/**
 * 版块本身的字段级回落,照抄 `BoardTreeLoad.mergeBoard` 的口径:
 * 结构以服务端为准,只有服务端这次没给的字段才用旧值补齐。
 *
 * 身份对不上(换了版块)时不合并——那是两个版块的元信息,补齐等于串味。
 */
private fun keepBoardFields(previous: Board?, fresh: Board?): Board? {
  if (fresh == null) return previous
  if (previous == null || previous.id != fresh.id || previous.kind != fresh.kind) return fresh
  return fresh.copy(
    info = fresh.info ?: previous.info,
    iconUrl = fresh.iconUrl ?: previous.iconUrl,
    // 版头:`topped_topic` 为 0/空串时解析成 null,与「`__F` 没给」在这一层同形
    head = fresh.head ?: previous.head,
  )
}
