package com.chasel.ng2n.data.settings

const val BOARD_TREE_TTL_MS = 24L * 60 * 60 * 1000

data class CachedBoardTree(
  val payload: String,
  val fetchedAt: Long,
)

fun isBoardTreeStale(fetchedAt: Long, now: Long, ttl: Long = BOARD_TREE_TTL_MS): Boolean {
  val elapsed = now - fetchedAt
  return elapsed < 0 || elapsed >= ttl
}

const val DISMISSED_ANNOUNCEMENTS_LIMIT = 20

fun withDismissedAnnouncement(ids: List<String>, id: String): List<String> =
  (ids.filter { it != id } + id).takeLast(DISMISSED_ANNOUNCEMENTS_LIMIT)
