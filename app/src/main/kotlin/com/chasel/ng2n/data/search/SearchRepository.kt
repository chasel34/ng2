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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class TopicKey(
    val query: String,
    val boardId: Long? = null,
    val kind: BoardKind = BoardKind.BOARD,
    val content: Boolean = false,
  )

  data class TopicState(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val error: Throwable? = null,
    val loadingNextPage: Boolean = false,
    val hasNextPage: Boolean = true,
  ) {
    val totalRows: Long get() = pages.firstOrNull()?.totalRows ?: 0
  }

  data class BoardState(
    val loading: Boolean = true,
    val items: List<BoardSearchItem> = emptyList(),
    val error: Throwable? = null,
    val loaded: Boolean = false,
  )

  data class UserState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val error: Throwable? = null,
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

  suspend fun ensureTopicPage(key: TopicKey) {
    if (key.query.isEmpty()) return
    if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return
    lockOf(key).withLock {
      if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return@withLock
      putTopic(key) { it.copy(loading = true, error = null) }
      fetchTopicsInto(key, page = 1, replace = true)
    }
  }

  suspend fun retryTopics(key: TopicKey) {
    if (key.query.isEmpty()) return
    lockOf(key).withLock {
      client.forgetSuccessfulCombo("thread.php")
      putTopic(key) { TopicState(loading = true) }
      fetchTopicsInto(key, page = 1, replace = true)
    }
  }

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

  private suspend fun fetchTopicsInto(key: TopicKey, page: Int, replace: Boolean) = withContext(Dispatchers.IO) {
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

  suspend fun ensureBoards(query: String) {
    if (query.isEmpty()) return
    if (boardBuckets.value[query]?.loaded == true) return
    reloadBoards(query)
  }

  suspend fun reloadBoards(query: String) {
    if (query.isEmpty()) return
    withContext(Dispatchers.IO) {
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
  }

  private fun putBoard(query: String, transform: (BoardState) -> BoardState) {
    val current = boardBuckets.value[query] ?: BoardState()
    boardBuckets.value = evict(boardBuckets.value + (query to transform(current)), query)
  }

  suspend fun ensureUser(query: String, now: Long = System.currentTimeMillis()) {
    if (parseUserSearchInput(query) == null) return
    val current = userBuckets.value[query]
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < USER_STALE_MS) return
    reloadUser(query, now)
  }

  suspend fun reloadUser(query: String, now: Long = System.currentTimeMillis()) {
    val parsed = parseUserSearchInput(query) ?: return
    withContext(Dispatchers.IO) {
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
  }

  private fun putUser(query: String, transform: (UserState) -> UserState) {
    val current = userBuckets.value[query] ?: UserState()
    userBuckets.value = evict(userBuckets.value + (query to transform(current)), query)
  }

  private fun <K, V> evict(map: Map<K, V>, keep: K): Map<K, V> {
    if (map.size <= MAX_ENTRIES) return map
    val victim = map.keys.firstOrNull { it != keep } ?: return map
    return map - victim
  }

  private companion object {
    const val MAX_ENTRIES = 8

    const val USER_STALE_MS = 5L * 60 * 1000

    fun hasNextPage(pages: List<TopicList>): Boolean {
      val last = pages.lastOrNull() ?: return true
      if (last.topics.isEmpty()) return false
      return pages.size + 1 <= last.totalPages
    }
  }
}
