package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.BoardGroup
import com.chasel.ng2n.core.api.BoardTree
import com.chasel.ng2n.data.settings.BOARD_TREE_TTL_MS
import com.chasel.ng2n.data.settings.isBoardTreeStale

data class BoardTreeSnapshot(val tree: BoardTree, val fetchedAt: Long)

interface BoardTreeStore {
  suspend fun read(): BoardTreeSnapshot?
  suspend fun write(snapshot: BoardTreeSnapshot)
}

enum class BoardTreeSource { CACHE, NETWORK }

data class BoardTreeLoadResult(
  val tree: BoardTree,
  val fetchedAt: Long,
  val source: BoardTreeSource,
  val error: Throwable? = null,
)

private fun mergeBoard(cached: Board?, fresh: Board): Board {
  if (cached == null) return fresh
  return fresh.copy(
    info = fresh.info ?: cached.info,
    iconUrl = fresh.iconUrl ?: cached.iconUrl,
  )
}

private fun indexBoards(tree: BoardTree): Map<Long, Board> {
  val index = LinkedHashMap<Long, Board>()
  for (category in tree.categories) {
    for (group in category.groups) {
      for (board in group.boards) {
        if (!index.containsKey(board.id)) index[board.id] = board
      }
    }
  }
  return index
}

fun mergeBoardTree(cached: BoardTree, fresh: BoardTree): BoardTree {
  val previous = indexBoards(cached)
  val categories: List<BoardCategory> = fresh.categories.map { category ->
    val groups: List<BoardGroup> = category.groups.map { group ->
      group.copy(boards = group.boards.map { mergeBoard(previous[it.id], it) })
    }
    category.copy(groups = groups)
  }
  return BoardTree(categories = categories, announcements = fresh.announcements)
}

suspend fun loadBoardTree(
  store: BoardTreeStore,
  fetchTree: suspend () -> BoardTree,
  now: Long,
  ttl: Long = BOARD_TREE_TTL_MS,
  force: Boolean = false,
): BoardTreeLoadResult {
  val cached = runCatching { store.read() }.getOrNull()

  if (!force && cached != null && !isBoardTreeStale(cached.fetchedAt, now, ttl)) {
    return BoardTreeLoadResult(cached.tree, cached.fetchedAt, BoardTreeSource.CACHE)
  }

  return try {
    val fetched = fetchTree()
    val next = BoardTreeSnapshot(
      tree = if (cached == null) fetched else mergeBoardTree(cached.tree, fetched),
      fetchedAt = now,
    )
    runCatching { store.write(next) }
    BoardTreeLoadResult(next.tree, next.fetchedAt, BoardTreeSource.NETWORK)
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
  } catch (error: Throwable) {
    if (cached == null) throw error
    BoardTreeLoadResult(cached.tree, cached.fetchedAt, BoardTreeSource.CACHE, error)
  }
}
