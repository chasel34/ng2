package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.api.fetchTopicList
import com.chasel.ng2n.core.api.mergeTopicPages
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

  /** 进屏时调。**幂等**:已经有第一页就什么都不做(返回时不该重打接口)。 */
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

  private suspend fun fetchInto(key: Key, page: Int, replace: Boolean) {
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
        val pages = if (replace) listOf(fetched) else state.pages + fetched
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
