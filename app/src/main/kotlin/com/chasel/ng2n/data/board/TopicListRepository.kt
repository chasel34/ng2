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

@Singleton
class TopicListRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(
    val boardId: Long,
    val kind: BoardKind,
    val sort: TopicSort,
    val recommend: Boolean = false,
  )

  data class State(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
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

  suspend fun loadOnEntry(key: Key, restored: Boolean) {
    if (restored) ensureFirstPage(key) else refresh(key)
  }

  suspend fun ensureFirstPage(key: Key) {
    if (entries.value[key]?.pages?.isNotEmpty() == true) {
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

  suspend fun refresh(key: Key) = lockOf(key).withLock {
    put(key) { it.copy(refreshing = true, error = null) }
    fetchInto(key, page = 1, replace = true)
    put(key) { it.copy(refreshing = false) }
  }

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
      val fetched = fetchTopicList(
        client = client,
        boardId = key.boardId,
        kind = key.kind,
        page = page,
        sort = key.sort,
        recommend = key.recommend,
      )
      put(key) { state ->
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

    fun hasNextPage(pages: List<TopicList>): Boolean {
      val last = pages.lastOrNull() ?: return true
      if (last.topics.isEmpty()) return false
      return pages.size + 1 <= last.totalPages
    }
  }
}

internal fun keepBoardMeta(previous: TopicList?, fresh: TopicList): TopicList {
  if (previous == null) return fresh
  return fresh.copy(
    board = keepBoardFields(previous.board, fresh.board),
    subBoards = fresh.subBoards.ifEmpty { previous.subBoards },
  )
}

private fun keepBoardFields(previous: Board?, fresh: Board?): Board? {
  if (fresh == null) return previous
  if (previous == null || previous.id != fresh.id || previous.kind != fresh.kind) return fresh
  return fresh.copy(
    info = fresh.info ?: previous.info,
    iconUrl = fresh.iconUrl ?: previous.iconUrl,
    head = fresh.head ?: previous.head,
  )
}
