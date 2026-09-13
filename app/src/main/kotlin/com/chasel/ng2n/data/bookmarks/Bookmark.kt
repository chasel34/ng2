package com.chasel.ng2n.data.bookmarks

import com.chasel.ng2n.data.history.HistoryEntry

const val BOOKMARK_NOTE_MAX = 100
const val BOOKMARK_SUMMARY_MAX = 80
const val IMAGE_ONLY_SUMMARY = "[图片]"
const val EMPTY_SUMMARY = "（无正文）"

data class Bookmark(
  val tid: Long,
  val pid: Long,
  val lou: Long,
  val author: String,
  val summary: String,
  val note: String?,
  val subject: String,
  val boardName: String?,
  val favCode: String?,
  /** Unix 秒。 */
  val createdAt: Long,
  /** Unix 秒。 */
  val updatedAt: Long,
)

data class BookmarkDraft(
  val tid: Long,
  val pid: Long,
  val lou: Long,
  val author: String,
  val summary: String,
  val note: String?,
  val subject: String,
  val boardName: String?,
  val favCode: String?,
)

data class BookmarkGroup(
  val tid: Long,
  val subject: String,
  val boardName: String?,
  val favCode: String?,
  val latestCreatedAt: Long,
  val bookmarks: List<Bookmark>,
)

data class BookmarkGroupRow(
  val group: BookmarkGroup,
  /** 阅读进度楼号；没有历史条目或只读过主楼时为空。 */
  val lastFloor: Long?,
)

fun groupBookmarks(bookmarks: List<Bookmark>): List<BookmarkGroup> =
  bookmarks
    .groupBy { it.tid }
    .map { (tid, rows) ->
      val newest = rows.maxBy { it.updatedAt }
      BookmarkGroup(
        tid = tid,
        subject = newest.subject,
        boardName = newest.boardName,
        favCode = newest.favCode,
        latestCreatedAt = rows.maxOf { it.createdAt },
        bookmarks = rows.sortedBy { it.lou },
      )
    }
    .sortedWith(compareByDescending<BookmarkGroup> { it.latestCreatedAt }.thenByDescending { it.tid })

fun attachProgress(groups: List<BookmarkGroup>, history: List<HistoryEntry>): List<BookmarkGroupRow> {
  val progress = history.associate { it.tid to it.lastFloor }
  return groups.map { group ->
    val lastFloor = progress[group.tid]?.takeIf { it >= 1 }?.toLong()
    BookmarkGroupRow(group = group, lastFloor = lastFloor)
  }
}

fun bookmarkSummary(plainText: String, hasImages: Boolean): String {
  val compact = plainText.replace(WHITESPACE, " ").trim()
  return when {
    compact.isNotEmpty() -> compact.take(BOOKMARK_SUMMARY_MAX)
    hasImages -> IMAGE_ONLY_SUMMARY
    else -> EMPTY_SUMMARY
  }
}

fun normalizeNote(input: String?): String? =
  input?.trim()?.take(BOOKMARK_NOTE_MAX)?.takeIf { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")
