package com.chasel.ng2n.data.bookmarks

import com.chasel.ng2n.data.db.BookmarkDao
import com.chasel.ng2n.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(private val dao: BookmarkDao) {

  fun observeGroups(): Flow<List<BookmarkGroup>> =
    dao.observeAll().map { rows -> groupBookmarks(rows.map { it.toBookmark() }) }

  fun observeTopic(tid: Long): Flow<List<Bookmark>> =
    dao.observeByTid(tid).map { rows -> rows.map { it.toBookmark() } }

  suspend fun find(tid: Long, pid: Long): Bookmark? = dao.find(tid, pid)?.toBookmark()

  suspend fun count(): Int = dao.count()

  /** 同一楼层已有书签时只改备注并刷新更新时间，创建时间保留。 */
  suspend fun save(draft: BookmarkDraft, nowSeconds: Long): Bookmark {
    val existing = dao.find(draft.tid, draft.pid)
    val row = BookmarkEntity(
      tid = draft.tid,
      pid = draft.pid,
      lou = existing?.lou ?: draft.lou,
      author = existing?.author ?: draft.author,
      summary = existing?.summary ?: draft.summary,
      note = normalizeNote(draft.note),
      subject = draft.subject.ifBlank { existing?.subject ?: "" },
      boardName = draft.boardName ?: existing?.boardName,
      favCode = draft.favCode ?: existing?.favCode,
      createdAt = existing?.createdAt ?: nowSeconds,
      updatedAt = nowSeconds,
    )
    dao.upsert(row)
    return row.toBookmark()
  }

  suspend fun remove(tid: Long, pid: Long): Bookmark? {
    val existing = dao.find(tid, pid) ?: return null
    dao.delete(tid, pid)
    return existing.toBookmark()
  }

  suspend fun restore(bookmark: Bookmark) {
    dao.upsert(bookmark.toEntity())
  }

  suspend fun removeTopic(tid: Long) {
    dao.deleteByTid(tid)
  }

  suspend fun clear() {
    dao.clear()
  }

  /** 新值缺失（空标题或 null）时保留旧值。 */
  suspend fun refreshTopicMeta(tid: Long, subject: String?, boardName: String?, favCode: String?) {
    val current = dao.byTid(tid).firstOrNull() ?: return
    val nextSubject = subject?.takeIf { it.isNotBlank() } ?: current.subject
    val nextBoard = boardName ?: current.boardName
    val nextFav = favCode ?: current.favCode
    if (nextSubject == current.subject && nextBoard == current.boardName && nextFav == current.favCode) return
    dao.updateTopicMeta(tid, nextSubject, nextBoard, nextFav)
  }
}

internal fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
  tid = tid,
  pid = pid,
  lou = lou,
  author = author,
  summary = summary,
  note = note,
  subject = subject,
  boardName = boardName,
  favCode = favCode,
  createdAt = createdAt,
  updatedAt = updatedAt,
)

internal fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
  tid = tid,
  pid = pid,
  lou = lou,
  author = author,
  summary = summary,
  note = note,
  subject = subject,
  boardName = boardName,
  favCode = favCode,
  createdAt = createdAt,
  updatedAt = updatedAt,
)
