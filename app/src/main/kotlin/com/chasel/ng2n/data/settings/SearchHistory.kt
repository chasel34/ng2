package com.chasel.ng2n.data.settings

import kotlinx.serialization.Serializable

enum class SearchTab(val wire: String) {
  TOPICS("topics"), BOARDS("boards"), USERS("users");

  companion object {
    fun fromWire(value: String?): SearchTab? = entries.firstOrNull { it.wire == value }
  }
}

@Serializable
data class SearchBoardScope(
  val boardId: Long,
  val kind: String,
  val name: String,
)

@Serializable
data class SearchHistoryEntry(
  val query: String,
  val scope: SearchBoardScope? = null,
  val content: Boolean? = null,
)

const val SEARCH_HISTORY_LIMIT = 20

@Serializable
data class SearchHistory(
  val topics: List<SearchHistoryEntry> = emptyList(),
  val boards: List<SearchHistoryEntry> = emptyList(),
  val users: List<SearchHistoryEntry> = emptyList(),
) {
  fun of(tab: SearchTab): List<SearchHistoryEntry> = when (tab) {
    SearchTab.TOPICS -> topics
    SearchTab.BOARDS -> boards
    SearchTab.USERS -> users
  }

  fun with(tab: SearchTab, entries: List<SearchHistoryEntry>): SearchHistory = when (tab) {
    SearchTab.TOPICS -> copy(topics = entries)
    SearchTab.BOARDS -> copy(boards = entries)
    SearchTab.USERS -> copy(users = entries)
  }
}

val EMPTY_SEARCH_HISTORY = SearchHistory()

private fun sameEntry(a: SearchHistoryEntry, b: SearchHistoryEntry): Boolean =
  a.query == b.query &&
    a.scope?.boardId == b.scope?.boardId &&
    (a.content == true) == (b.content == true)

fun addSearchHistory(
  history: SearchHistory,
  tab: SearchTab,
  entry: SearchHistoryEntry,
): SearchHistory {
  val kept = history.of(tab).filterNot { sameEntry(it, entry) }
  return history.with(tab, (listOf(entry) + kept).take(SEARCH_HISTORY_LIMIT))
}

fun removeSearchHistory(history: SearchHistory, tab: SearchTab, index: Int): SearchHistory =
  history.with(tab, history.of(tab).filterIndexed { i, _ -> i != index })

fun clearSearchHistory(history: SearchHistory, tab: SearchTab): SearchHistory =
  history.with(tab, emptyList())

fun sanitizeSearchHistory(history: SearchHistory): SearchHistory = SearchHistory(
  topics = history.topics.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
  boards = history.boards.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
  users = history.users.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
)
