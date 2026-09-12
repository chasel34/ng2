package com.chasel.ng2n.data.settings

import kotlinx.serialization.Serializable

/**
 * 搜索历史 —— `src/store/search.ts` 的存储那一半(MMKV key `search/history`)。
 *
 * 三个 tab 各自独立、各留最近 20 条,新搜的在前。
 * **纯 Kotlin**;落盘在 [SettingsStore]。
 */

/** 搜索页的三个 tab(设计稿:搜主题 / 搜板块 / 搜用户)。 */
enum class SearchTab(val wire: String) {
  TOPICS("topics"), BOARDS("boards"), USERS("users");

  companion object {
    fun fromWire(value: String?): SearchTab? = entries.firstOrNull { it.wire == value }
  }
}

/** 主题搜索的版块限定(「当前板块」单选)。不带 = 全部板块。 */
@Serializable
data class SearchBoardScope(
  val boardId: Long,
  /** `board` 或 `collection`(合集,走 `stid`) */
  val kind: String,
  val name: String,
)

/** 一条搜索历史:词 + 当时的范围(主题 tab 才有 scope/content,重搜时原样还原)。 */
@Serializable
data class SearchHistoryEntry(
  val query: String,
  val scope: SearchBoardScope? = null,
  val content: Boolean? = null,
)

/** 每个 tab 各留最近若干条,设计稿一屏画了 6 条,20 足够翻一翻。 */
const val SEARCH_HISTORY_LIMIT = 20

/** 三个 tab 的历史。 */
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

/** 同一个词同一种范围只留一条:重搜挪到最前,不同范围算不同历史(标注不一样)。 */
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

/** 落盘的历史读回来:query 为空的条目是脏数据,跳过而不是整份丢掉。 */
fun sanitizeSearchHistory(history: SearchHistory): SearchHistory = SearchHistory(
  topics = history.topics.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
  boards = history.boards.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
  users = history.users.filter { it.query != "" }.take(SEARCH_HISTORY_LIMIT),
)
