package com.chasel.ng2n.data.user

import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.UserPostKind
import com.chasel.ng2n.core.api.fetchUserTopics
import com.chasel.ng2n.core.api.hasMoreUserPosts
import com.chasel.ng2n.core.api.mergeUserPostPages
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
 * 某人的主题 / 某人的回复,往下翻页 —— 直译 RN 侧 `src/store/user-topics.ts`。
 *
 * 与 `TopicListRepository` 分开而不是加个开关,因为两处判据都不一样:
 *
 * - **翻到底看 [hasMoreUserPosts]**(这一页是不是一条都没有),不是 `totalPages` ——
 *   回复列表的 `__ROWS` 是空串,总页数根本算不出来;
 * - **去重按 `reply.pid`**([mergeUserPostPages]),不是 tid —— 一个帖子里回了 10 层
 *   就是正当的 10 条。
 *
 * 两条的出处都在 `core/api/UserTopics.kt` 的 KDoc 里。
 */
@Singleton
class UserPostsRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(val uid: Long, val kind: UserPostKind)

  data class State(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    /** 已按 pid(没有 reply 的退回 tid)去重的拼页结果 */
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
    var next = entries.value + (key to transform(entries.value[key] ?: State()))
    touched[key] = System.nanoTime()
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
    if (key.uid <= 0) return
    if (entries.value[key]?.pages?.isNotEmpty() == true) {
      put(key) { it }
      return
    }
    lockOf(key).withLock {
      if (entries.value[key]?.pages?.isNotEmpty() == true) return@withLock
      put(key) { it.copy(loading = true, error = null) }
      fetchInto(key, page = 1, replace = true)
    }
  }

  /**
   * 下拉刷新:**先把已翻的页砍到只剩第一页,再重取**(与主题列表同一口径)——
   * 全量重取会把每一页都重打一遍,正好撞在 NGA 封第三方客户端的枪口上(ADR-0002)。
   */
  suspend fun refresh(key: Key) = lockOf(key).withLock {
    put(key) { it.copy(refreshing = true, error = null) }
    fetchInto(key, page = 1, replace = true)
    put(key) { it.copy(refreshing = false) }
  }

  /**
   * 「重试」:除了重取,还要**忘掉 `thread.php` 上次试通的格式 × 域名组合**。
   * 用户按这个按钮的时候,恰恰是「拿回来的东西不对」的时候,而反封锁链会优先复用
   * 上次成功的组合 —— 不清掉的话按一百次也还是从同一个坏组合开局。
   *
   * ⚠️ `thread.php` 这一条是版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 / 某人的主题
   * **共用**的一槽,清它等于让这半个 app 一起重新试探。RN 版就是这个口径。
   */
  suspend fun retry(key: Key) = lockOf(key).withLock {
    client.forgetSuccessfulCombo("thread.php")
    put(key) { State(loading = true) }
    fetchInto(key, page = 1, replace = true)
  }

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
      val fetched = fetchUserTopics(client, uid = key.uid, kind = key.kind, page = page)
      put(key) { state ->
        val pages = if (replace) listOf(fetched) else state.pages + fetched
        state.copy(
          loading = false,
          pages = pages,
          topics = mergeUserPostPages(pages),
          error = null,
          hasNextPage = hasMoreUserPosts(fetched),
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
    const val MAX_ENTRIES = 4
  }
}
