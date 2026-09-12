package com.chasel.ng2n.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 三张表的 DAO。**全 suspend / Flow** —— 没有一个同步方法,
 * 冷启动路径上想同步读磁盘都读不着(修 P2-04 的一半;另一半是
 * `Ng2nApplication` 里的 StrictMode 把它钉死)。
 *
 * DAO 只做**薄层**:上限、驱逐、节流的判断全在 各包下的 `…Policy.kt` 那些纯 Kotlin 类里,
 * 好让 JVM 单测跑得起来(票 14 验收项①,不引 Robolectric)。
 */

@Dao
interface BrowseHistoryDao {

  /** 历史页订阅。按最近浏览倒序,只取上限内的。 */
  @Query("SELECT * FROM browse_history ORDER BY updated_at DESC LIMIT :limit")
  fun observe(limit: Int): Flow<List<BrowseHistoryEntity>>

  @Query("SELECT * FROM browse_history ORDER BY updated_at DESC LIMIT :limit")
  suspend fun loadAll(limit: Int): List<BrowseHistoryEntity>

  @Query("SELECT * FROM browse_history WHERE tid = :tid")
  suspend fun find(tid: Long): BrowseHistoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: BrowseHistoryEntity)

  @Query("DELETE FROM browse_history WHERE tid IN (:tids)")
  suspend fun deleteByTids(tids: List<Long>)

  @Query("DELETE FROM browse_history")
  suspend fun clear()

  /**
   * 一次变更只动一条 —— upsert 与进度前进都会把它挪到最前,所以只写这一行,
   * 顺带把被 LRU 挤出去的删掉。一个事务里做完。
   */
  @Transaction
  suspend fun applyChange(entry: BrowseHistoryEntity, evictedTids: List<Long>) {
    if (evictedTids.isNotEmpty()) deleteByTids(evictedTids)
    upsert(entry)
  }
}

@Dao
interface TopicCacheDao {

  /**
   * 「我的缓存」列表订阅。`payload` **不选**:一页十几万字符,
   * 几百页全灌进内存会把它吃光(RN 版 `loadMeta` 的同款理由)。
   */
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

  /**
   * 把某个主题的全部页标成「刚用过」。
   * LRU 说的是「最久未**用**」——只按写入时间淘汰的话,天天离线翻的那个帖会先被挤掉。
   */
  @Query("UPDATE topic_cache SET used_at = :usedAt WHERE tid = :tid")
  suspend fun touch(tid: Long, usedAt: Long)

  /** 整主题删 —— 只删某主题的第 3 页、留下 1、2、4 页,离线读到中间会突然断掉。 */
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
interface NotificationReadDao {

  @Query("SELECT id FROM notification_read WHERE uid = :uid")
  fun observeReadIds(uid: String): Flow<List<String>>

  @Query("SELECT id FROM notification_read WHERE uid = :uid")
  suspend fun readIds(uid: String): List<String>

  /** 只把这次新读到的写盘:进页会把整屏条目都报一遍,老 ID 不必反复 INSERT。 */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(rows: List<NotificationReadEntity>)

  /** 一键清空:服务端 del 成功后才动本地。 */
  @Query("DELETE FROM notification_read WHERE uid = :uid")
  suspend fun clearForUid(uid: String)
}
