package com.chasel.ng2n.data.bookmarks

import com.chasel.ng2n.data.db.BookmarkDao
import com.chasel.ng2n.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeBookmarkDao : BookmarkDao {
  private val rows = LinkedHashMap<Pair<Long, Long>, BookmarkEntity>()
  private val state = MutableStateFlow<List<BookmarkEntity>>(emptyList())

  private fun snapshot(): List<BookmarkEntity> =
    rows.values.sortedWith(compareBy({ it.tid }, { it.lou }))

  private fun publish() {
    state.value = snapshot()
  }

  override fun observeAll(): Flow<List<BookmarkEntity>> = state

  override fun observeByTid(tid: Long): Flow<List<BookmarkEntity>> =
    state.map { list -> list.filter { it.tid == tid } }

  override suspend fun byTid(tid: Long): List<BookmarkEntity> = snapshot().filter { it.tid == tid }

  override suspend fun find(tid: Long, pid: Long): BookmarkEntity? = rows[tid to pid]

  override suspend fun protectedTids(): List<Long> = rows.values.map { it.tid }.distinct()

  override suspend fun count(): Int = rows.size

  override suspend fun upsert(row: BookmarkEntity) {
    rows[row.tid to row.pid] = row
    publish()
  }

  override suspend fun delete(tid: Long, pid: Long) {
    rows.remove(tid to pid)
    publish()
  }

  override suspend fun deleteByTid(tid: Long) {
    rows.keys.filter { it.first == tid }.forEach { rows.remove(it) }
    publish()
  }

  override suspend fun clear() {
    rows.clear()
    publish()
  }

  override suspend fun updateTopicMeta(tid: Long, subject: String, boardName: String?, favCode: String?) {
    for ((key, value) in rows.toList()) {
      if (key.first == tid) rows[key] = value.copy(subject = subject, boardName = boardName, favCode = favCode)
    }
    publish()
  }
}
