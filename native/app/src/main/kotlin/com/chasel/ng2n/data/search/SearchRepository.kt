package com.chasel.ng2n.data.search

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.BoardSearchItem
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.UserProfile
import com.chasel.ng2n.core.api.UserSearchQuery
import com.chasel.ng2n.core.api.fetchBoardSearch
import com.chasel.ng2n.core.api.fetchTopicSearch
import com.chasel.ng2n.core.api.fetchUserProfile
import com.chasel.ng2n.core.api.fetchUserProfileByName
import com.chasel.ng2n.core.api.mergeTopicPages
import com.chasel.ng2n.core.api.parseUserSearchInput
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
 * 搜索三条路的仓库 —— 直译 RN 侧 `store/search.ts` 里那三个 query
 * (`useTopicSearch` / `useBoardSearch` / `useUserSearch`)。
 *
 * 搜索历史不在这里:它是**设置那一侧**的持久化数据,住 `data/settings/SearchHistory.kt`
 * (票 14 已落地)+ [com.chasel.ng2n.data.settings.SettingsStore.searchHistory]。
 *
 * ## 按 key 分桶,进程级
 *
 * 与 [com.chasel.ng2n.data.board.TopicListRepository] 同一套结构:
 * RN 侧这三份数据住在全局 query cache 里,从搜索结果点进主题再返回,列表与已翻的页
 * 都还在。key 里带上「范围 / 含正文」—— **换一种搜法就是另一份数据,不能混页**
 * (RN 侧 queryKey 就是这么排的)。
 *
 * ## 用户搜索的 5 分钟保鲜
 *
 * RN 侧 `useUserSearch` 上写着 `staleTime: 5 * 60 * 1000`,理由是「资料不常变,
 * 反复搜同一个人不该反复打 ucp」(ADR-0002:能少打就少打)。这里落成
 * [ensureUser] 的时间判据。
 */
@Singleton
class SearchRepository @Inject constructor(
  private val client: NgaClient,
) {

  /** 主题搜索的一份数据。范围与含正文进 key —— 换一种搜法就是另一份。 */
  data class TopicKey(
    val query: String,
    /** 限定版块:合集传 stid、普通版块传 fid;null = 全站 */
    val boardId: Long? = null,
    val kind: BoardKind = BoardKind.BOARD,
    /** 「包括正文」(`content=1`) */
    val content: Boolean = false,
  )

  data class TopicState(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    /** 已按 tid 去重的拼页结果 */
    val topics: List<Topic> = emptyList(),
    val error: Throwable? = null,
    val loadingNextPage: Boolean = false,
    val hasNextPage: Boolean = true,
  ) {
    /** 服务端给的命中总数(结果统计条那句「约 N 条结果」)。 */
    val totalRows: Long get() = pages.firstOrNull()?.totalRows ?: 0
  }

  data class BoardState(
    val loading: Boolean = true,
    val items: List<BoardSearchItem> = emptyList(),
    val error: Throwable? = null,
    /** 请求真的回来过(空列表要能与「还没搜」区分开) */
    val loaded: Boolean = false,
  )

  data class UserState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val error: Throwable? = null,
    /** 上一次取到的时刻;0 = 还没取过 */
    val fetchedAt: Long = 0,
  )

  private val topicBuckets = MutableStateFlow<Map<TopicKey, TopicState>>(emptyMap())
  val topicStates: StateFlow<Map<TopicKey, TopicState>> = topicBuckets.asStateFlow()

  private val boardBuckets = MutableStateFlow<Map<String, BoardState>>(emptyMap())
  val boardStates: StateFlow<Map<String, BoardState>> = boardBuckets.asStateFlow()

  private val userBuckets = MutableStateFlow<Map<String, UserState>>(emptyMap())
  val userStates: StateFlow<Map<String, UserState>> = userBuckets.asStateFlow()

  private val locks = HashMap<Any, Mutex>()

  @Synchronized
  private fun lockOf(key: Any): Mutex = locks.getOrPut(key) { Mutex() }

  fun topicStateOf(key: TopicKey): TopicState = topicBuckets.value[key] ?: TopicState()

  fun boardStateOf(query: String): BoardState = boardBuckets.value[query] ?: BoardState()

  fun userStateOf(query: String): UserState = userBuckets.value[query] ?: UserState()

  // ---------------------------------------------------------------- 主题

  /** 进结果页时调。**幂等**:已经有第一页就什么都不做(返回时不该重打接口)。 */
  suspend fun ensureTopicPage(key: TopicKey) {
    if (key.query.isEmpty()) return
    if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return
    lockOf(key).withLock {
      if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return@withLock
      putTopic(key) { it.copy(loading = true, error = null) }
      fetchTopicsInto(key, page = 1, replace = true)
    }
  }

  /**
   * 「重试」:除了重新请求,还要忘掉 `thread.php` 上次试通的组合 ——
   * 与版块列表同一条理由(2026-08-13「版块全空」排查),而且这两条路本来就共用
   * 同一条 comboCache 记录。
   */
  suspend fun retryTopics(key: TopicKey) {
    if (key.query.isEmpty()) return
    lockOf(key).withLock {
      client.forgetSuccessfulCombo("thread.php")
      putTopic(key) { TopicState(loading = true) }
      fetchTopicsInto(key, page = 1, replace = true)
    }
  }

  /** 无限滚动的下一页。到底了 / 正在翻 / 上一次失败时都不发。 */
  suspend fun loadNextTopicPage(key: TopicKey) {
    val current = topicStateOf(key)
    if (!current.hasNextPage || current.loadingNextPage || current.loading) return
    lockOf(key).withLock {
      val now = topicStateOf(key)
      if (!now.hasNextPage || now.loadingNextPage) return@withLock
      putTopic(key) { it.copy(loadingNextPage = true, error = null) }
      fetchTopicsInto(key, page = now.pages.size + 1, replace = false)
      putTopic(key) { it.copy(loadingNextPage = false) }
    }
  }

  private suspend fun fetchTopicsInto(key: TopicKey, page: Int, replace: Boolean) {
    try {
      val fetched = fetchTopicSearch(
        client = client,
        key = key.query,
        page = page,
        boardId = key.boardId,
        kind = key.kind,
        searchContent = key.content,
      )
      putTopic(key) { state ->
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
      putTopic(key) { it.copy(loading = false, loadingNextPage = false) }
      throw cancelled
    } catch (error: Throwable) {
      putTopic(key) { it.copy(loading = false, error = error) }
    }
  }

  private fun putTopic(key: TopicKey, transform: (TopicState) -> TopicState) {
    val current = topicBuckets.value[key] ?: TopicState()
    topicBuckets.value = evict(topicBuckets.value + (key to transform(current)), key)
  }

  // ---------------------------------------------------------------- 版块

  /** 版块搜索一次给全量(实测上限 100 条),没有分页。 */
  suspend fun ensureBoards(query: String) {
    if (query.isEmpty()) return
    if (boardBuckets.value[query]?.loaded == true) return
    reloadBoards(query)
  }

  suspend fun reloadBoards(query: String) {
    if (query.isEmpty()) return
    lockOf("boards/$query").withLock {
      putBoard(query) { it.copy(loading = true, error = null) }
      try {
        val items = fetchBoardSearch(client, query)
        putBoard(query) { BoardState(loading = false, items = items, loaded = true) }
      } catch (cancelled: CancellationException) {
        putBoard(query) { it.copy(loading = false) }
        throw cancelled
      } catch (error: Throwable) {
        putBoard(query) { it.copy(loading = false, error = error) }
      }
    }
  }

  private fun putBoard(query: String, transform: (BoardState) -> BoardState) {
    val current = boardBuckets.value[query] ?: BoardState()
    boardBuckets.value = evict(boardBuckets.value + (query to transform(current)), query)
  }

  // ---------------------------------------------------------------- 用户

  /**
   * 用户搜索:纯数字按 uid、否则按用户名走 ucp 资料查询(`core/api/Search.kt`)。
   * 查无此人是 server 错误(「找不到用户」),落在 [UserState.error] 上由结果页措辞。
   */
  suspend fun ensureUser(query: String, now: Long = System.currentTimeMillis()) {
    if (parseUserSearchInput(query) == null) return
    val current = userBuckets.value[query]
    // RN 侧 staleTime 5min:反复搜同一个人不该反复打 ucp
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < USER_STALE_MS) return
    reloadUser(query, now)
  }

  suspend fun reloadUser(query: String, now: Long = System.currentTimeMillis()) {
    val parsed = parseUserSearchInput(query) ?: return
    lockOf("user/$query").withLock {
      putUser(query) { it.copy(loading = true, error = null) }
      try {
        val profile = when (parsed) {
          is UserSearchQuery.Uid -> fetchUserProfile(client, parsed.uid)
          is UserSearchQuery.Username -> fetchUserProfileByName(client, parsed.username)
        }
        putUser(query) { UserState(loading = false, profile = profile, fetchedAt = now) }
      } catch (cancelled: CancellationException) {
        putUser(query) { it.copy(loading = false) }
        throw cancelled
      } catch (error: Throwable) {
        putUser(query) { it.copy(loading = false, error = error) }
      }
    }
  }

  private fun putUser(query: String, transform: (UserState) -> UserState) {
    val current = userBuckets.value[query] ?: UserState()
    userBuckets.value = evict(userBuckets.value + (query to transform(current)), query)
  }

  // ---------------------------------------------------------------- 公共

  /**
   * 桶数上限。搜索是「搜完就走」的场景,一个人一次会话里翻不了几种搜法;
   * 满了按插入顺序丢最老的(`LinkedHashMap` 的顺序即插入顺序,对应 TanStack 的 gcTime)。
   */
  private fun <K, V> evict(map: Map<K, V>, keep: K): Map<K, V> {
    if (map.size <= MAX_ENTRIES) return map
    val victim = map.keys.firstOrNull { it != keep } ?: return map
    return map - victim
  }

  private companion object {
    const val MAX_ENTRIES = 8

    /** RN 侧 `useUserSearch` 的 `staleTime`。 */
    const val USER_STALE_MS = 5L * 60 * 1000

    /**
     * 还有没有下一页(RN 侧 `getNextPageParam`):
     * 空页 = 到底了(没有结果 / 翻过头都归一成空页),别再打同一个空响应。
     */
    fun hasNextPage(pages: List<TopicList>): Boolean {
      val last = pages.lastOrNull() ?: return true
      if (last.topics.isEmpty()) return false
      return pages.size + 1 <= last.totalPages
    }
  }
}
