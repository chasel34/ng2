package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.fetchHotTopics
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HotTopicsRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(val boardId: Long, val kind: BoardKind)

  data class State(
    val loading: Boolean = true,
    val topics: List<Topic> = emptyList(),
    val failedPages: List<Int> = emptyList(),
    val pagesTried: Int = 0,
    val fetchedAt: Long = 0,
    val error: Throwable? = null,
    val refreshing: Boolean = false,
  )

  private val entries = MutableStateFlow<Map<Key, State>>(emptyMap())
  val states: StateFlow<Map<Key, State>> = entries.asStateFlow()

  fun stateOf(key: Key): State = entries.value[key] ?: State()

  private fun put(key: Key, transform: (State) -> State) {
    entries.value = entries.value + (key to transform(entries.value[key] ?: State()))
  }

  suspend fun ensureLoaded(key: Key, now: Long = System.currentTimeMillis()) {
    val current = entries.value[key]
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < STALE_MS) return
    load(key, now, refreshing = false)
  }

  suspend fun refresh(key: Key, now: Long = System.currentTimeMillis()) =
    load(key, now, refreshing = true)

  private suspend fun load(key: Key, now: Long, refreshing: Boolean) = withContext(Dispatchers.IO) {
    put(key) { it.copy(loading = it.fetchedAt == 0L, refreshing = refreshing, error = null) }
    try {
      val result = fetchHotTopics(
        client = client,
        boardId = key.boardId,
        kind = key.kind,
        nowSeconds = Math.floorDiv(now, 1000L),
      )
      put(key) {
        State(
          loading = false,
          topics = result.topics,
          failedPages = result.failedPages,
          pagesTried = result.pagesTried,
          fetchedAt = now,
          error = null,
          refreshing = false,
        )
      }
    } catch (cancelled: CancellationException) {
      put(key) { it.copy(loading = false, refreshing = false) }
      throw cancelled
    } catch (error: Throwable) {
      put(key) { it.copy(loading = false, refreshing = false, error = error) }
    }
  }

  private companion object {
    const val STALE_MS = 5L * 60 * 1000
  }
}
