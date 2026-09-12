package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.addBoardFavorite
import com.chasel.ng2n.core.api.clearBoardFavorites
import com.chasel.ng2n.core.api.fetchBoardFavorites
import com.chasel.ng2n.core.api.removeBoardFavorite
import com.chasel.ng2n.core.net.NgaClient
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
class BoardFavoriteRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class FavoritesState(
    val loading: Boolean = false,
    val boards: List<Board> = emptyList(),
    val error: Throwable? = null,
    val fetchedAt: Long = 0,
  )

  private val buckets = MutableStateFlow<Map<String, FavoritesState>>(emptyMap())
  val states: StateFlow<Map<String, FavoritesState>> = buckets.asStateFlow()

  private val lock = Mutex()

  fun stateOf(uid: String?): FavoritesState =
    if (uid == null) GUEST else buckets.value[uid] ?: FavoritesState()

  private fun put(uid: String, transform: (FavoritesState) -> FavoritesState) {
    val current = buckets.value[uid] ?: FavoritesState()
    buckets.value = buckets.value + (uid to transform(current))
  }

  suspend fun ensureLoaded(uid: String?, now: Long = System.currentTimeMillis()) {
    if (uid == null) return
    val current = buckets.value[uid]
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < STALE_MS) return
    if (current?.loading == true) return
    reload(uid, now)
  }

  suspend fun reload(uid: String?, now: Long = System.currentTimeMillis()) {
    if (uid == null) return
    withContext(Dispatchers.IO) {
      put(uid) { it.copy(loading = true) }
      try {
        val boards = fetchBoardFavorites(client)
        put(uid) { FavoritesState(loading = false, boards = boards, error = null, fetchedAt = now) }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        put(uid) { it.copy(loading = false) }
        throw cancelled
      } catch (error: Throwable) {
        put(uid) { it.copy(loading = false, error = error) }
      }
    }
  }

  suspend fun add(uid: String, board: Board) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = listOf(board) + it.boards.filter { kept -> kept.id != board.id }) }
    runOrRollback(uid, previous) { addBoardFavorite(client, board.id) }
  }

  suspend fun remove(uid: String, board: Board) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = it.boards.filter { kept -> kept.id != board.id }) }
    runOrRollback(uid, previous) { removeBoardFavorite(client, board.id) }
  }

  suspend fun clear(uid: String): List<Board> = withContext(Dispatchers.IO) {
    val previous = stateOf(uid).boards
    put(uid) { it.copy(boards = emptyList()) }
    try {
      val removed = clearBoardFavorites(client)
      reload(uid)
      removed
    } catch (error: Throwable) {
      put(uid) { it.copy(boards = previous) }
      reload(uid)
      throw error
    }
  }

  suspend fun restore(uid: String, boards: List<Board>) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = boards) }
    runOrRollback(uid, previous) {
      for (board in boards.reversed()) addBoardFavorite(client, board.id)
    }
  }

  suspend fun addById(uid: String, id: Long, provisional: Board): Board {
    add(uid, provisional)
    return stateOf(uid).boards.firstOrNull { it.id == id || it.fid == id || it.stid == id }
      ?: provisional
  }

  private suspend fun mutate(uid: String, block: suspend (List<Board>) -> Unit) {
    withContext(Dispatchers.IO) {
      lock.withLock {
        val previous = stateOf(uid).boards
        block(previous)
      }
    }
  }

  private suspend fun runOrRollback(uid: String, previous: List<Board>, action: suspend () -> Unit) {
    try {
      action()
    } catch (error: Throwable) {
      put(uid) { it.copy(boards = previous) }
      throw error
    } finally {
      runCatching { reload(uid) }
    }
  }

  private companion object {
    val GUEST = FavoritesState()

    const val STALE_MS = 5L * 60 * 1000
  }
}

fun List<Board>.withIconsFrom(icons: Map<Long, String>): List<Board> {
  if (icons.isEmpty()) return this
  var patched = false
  val next = map { board ->
    if (board.iconUrl != null) return@map board
    val url = icons[board.id] ?: return@map board
    patched = true
    board.copy(iconUrl = url)
  }
  return if (patched) next else this
}
