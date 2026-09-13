package com.chasel.ng2n.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowseHistoryDao {

  @Query("SELECT * FROM browse_history ORDER BY updated_at DESC LIMIT :limit")
  fun observe(limit: Int): Flow<List<BrowseHistoryEntity>>

  @Query("SELECT * FROM browse_history ORDER BY updated_at DESC LIMIT :limit")
  suspend fun loadAll(limit: Int): List<BrowseHistoryEntity>

  @Query("SELECT * FROM browse_history ORDER BY updated_at DESC")
  suspend fun loadAll(): List<BrowseHistoryEntity>

  @Query("SELECT * FROM browse_history WHERE tid = :tid")
  suspend fun find(tid: Long): BrowseHistoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: BrowseHistoryEntity)

  @Query("DELETE FROM browse_history WHERE tid IN (:tids)")
  suspend fun deleteByTids(tids: List<Long>)

  @Query("DELETE FROM browse_history")
  suspend fun clear()

  @Transaction
  suspend fun applyChange(entry: BrowseHistoryEntity, evictedTids: List<Long>) {
    if (evictedTids.isNotEmpty()) deleteByTids(evictedTids)
    upsert(entry)
  }
}

@Dao
interface TopicCacheDao {

  @Query(
    """
    SELECT tid, page, subject, board_name, fav_code, floors, total_pages, bytes, used_at
    FROM topic_cache
    """,
  )
  fun observeMeta(): Flow<List<TopicCacheMeta>>

  @Query(
    """
    SELECT tid, page, subject, board_name, fav_code, floors, total_pages, bytes, used_at
    FROM topic_cache
    """,
  )
  suspend fun loadMeta(): List<TopicCacheMeta>

  @Query("SELECT payload FROM topic_cache WHERE tid = :tid AND page = :page")
  suspend fun readPayload(tid: Long, page: Int): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: TopicCacheEntity)

  @Query("UPDATE topic_cache SET used_at = :usedAt WHERE tid = :tid")
  suspend fun touch(tid: Long, usedAt: Long)

  @Query("DELETE FROM topic_cache WHERE tid IN (:tids)")
  suspend fun deleteTopics(tids: List<Long>)

  @Query("DELETE FROM topic_cache")
  suspend fun clear()

  @Transaction
  suspend fun upsertAndEvict(entry: TopicCacheEntity, evictedTids: List<Long>) {
    upsert(entry)
    if (evictedTids.isNotEmpty()) deleteTopics(evictedTids)
  }
}

@Dao
interface BookmarkDao {

  @Query("SELECT * FROM bookmark ORDER BY tid, lou")
  fun observeAll(): Flow<List<BookmarkEntity>>

  @Query("SELECT * FROM bookmark WHERE tid = :tid ORDER BY lou")
  fun observeByTid(tid: Long): Flow<List<BookmarkEntity>>

  @Query("SELECT * FROM bookmark WHERE tid = :tid ORDER BY lou")
  suspend fun byTid(tid: Long): List<BookmarkEntity>

  @Query("SELECT * FROM bookmark WHERE tid = :tid AND pid = :pid")
  suspend fun find(tid: Long, pid: Long): BookmarkEntity?

  @Query("SELECT DISTINCT tid FROM bookmark")
  suspend fun protectedTids(): List<Long>

  @Query("SELECT COUNT(*) FROM bookmark")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(row: BookmarkEntity)

  @Query("DELETE FROM bookmark WHERE tid = :tid AND pid = :pid")
  suspend fun delete(tid: Long, pid: Long)

  @Query("DELETE FROM bookmark WHERE tid = :tid")
  suspend fun deleteByTid(tid: Long)

  @Query("DELETE FROM bookmark")
  suspend fun clear()

  @Query(
    "UPDATE bookmark SET subject = :subject, board_name = :boardName, fav_code = :favCode WHERE tid = :tid",
  )
  suspend fun updateTopicMeta(tid: Long, subject: String, boardName: String?, favCode: String?)
}

@Dao
interface NotificationReadDao {

  @Query("SELECT id FROM notification_read WHERE uid = :uid")
  fun observeReadIds(uid: String): Flow<List<String>>

  @Query("SELECT id FROM notification_read WHERE uid = :uid")
  suspend fun readIds(uid: String): List<String>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(rows: List<NotificationReadEntity>)

  @Query("DELETE FROM notification_read WHERE uid = :uid")
  suspend fun clearForUid(uid: String)
}
