package com.chasel.ng2n.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ng2nDatabaseTest {

  private lateinit var db: Ng2nDatabase

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = Room.inMemoryDatabaseBuilder(context, Ng2nDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() = db.close()

  private fun history(tid: Long, updatedAt: Long, lastFloor: Int = 0) = BrowseHistoryEntity(
    tid = tid,
    subject = "主题 $tid",
    author = null,
    boardName = null,
    favCode = null,
    lastFloor = lastFloor,
    maxFloor = 40,
    updatedAt = updatedAt,
  )

  @Test
  fun 历史按_tid_主键去重_同_tid_再写是更新而不是新增() = runTest {
    val dao = db.browseHistoryDao()
    dao.upsert(history(7, updatedAt = 100))
    dao.upsert(history(7, updatedAt = 200, lastFloor = 18))

    val rows = dao.loadAll(200)
    assertEquals(1, rows.size)
    assertEquals(200L, rows[0].updatedAt)
    assertEquals(18, rows[0].lastFloor)
  }

  @Test
  fun 历史按_updated_at_倒序取_且尊重上限() = runTest {
    val dao = db.browseHistoryDao()
    (1..5).forEach { dao.upsert(history(it.toLong(), updatedAt = it * 100L)) }

    assertEquals(listOf(5L, 4L, 3L), dao.loadAll(3).map { it.tid })
  }

  @Test
  fun 一个事务里写一条并删掉被淘汰的() = runTest {
    val dao = db.browseHistoryDao()
    dao.upsert(history(1, updatedAt = 100))
    dao.upsert(history(2, updatedAt = 200))
    dao.applyChange(history(3, updatedAt = 300), evictedTids = listOf(1))

    assertEquals(listOf(3L, 2L), dao.loadAll(200).map { it.tid })
    assertNull(dao.find(1))
  }

  private fun cache(tid: Long, page: Int, usedAt: Long, payload: String = "{}") = TopicCacheEntity(
    tid = tid,
    page = page,
    subject = "主题 $tid",
    boardName = null,
    favCode = null,
    floors = 20,
    totalPages = 3,
    bytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
    payload = payload,
    usedAt = usedAt,
  )

  @Test
  fun 缓存按_tid_与_page_联合主键_同一页重写是更新() = runTest {
    val dao = db.topicCacheDao()
    dao.upsert(cache(7, 1, usedAt = 100, payload = "旧"))
    dao.upsert(cache(7, 1, usedAt = 200, payload = "新"))

    assertEquals(1, dao.loadMeta().size)
    assertEquals("新", dao.readPayload(7, 1))
  }

  @Test
  fun 元数据投影不带_payload_而_readPayload_能取到正文() = runTest {
    val dao = db.topicCacheDao()
    dao.upsert(cache(7, 1, usedAt = 100, payload = "一大段信封"))

    val meta = dao.loadMeta().single()
    assertEquals(7L, meta.tid)
    assertEquals("一大段信封".toByteArray(Charsets.UTF_8).size.toLong(), meta.bytes)
    assertEquals("一大段信封", dao.readPayload(7, 1))
    assertNull(dao.readPayload(7, 2))
  }

  @Test
  fun touch_把整个主题的_used_at_推到现在() = runTest {
    val dao = db.topicCacheDao()
    dao.upsert(cache(7, 1, usedAt = 100))
    dao.upsert(cache(7, 2, usedAt = 110))
    dao.upsert(cache(8, 1, usedAt = 120))

    dao.touch(7, usedAt = 900)

    val byTid = dao.loadMeta().groupBy { it.tid }
    assertTrue(byTid.getValue(7).all { it.usedAt == 900L })
    assertEquals(120L, byTid.getValue(8).single().usedAt)
  }

  @Test
  fun 驱逐是整主题走的() = runTest {
    val dao = db.topicCacheDao()
    dao.upsert(cache(7, 1, usedAt = 100))
    dao.upsert(cache(7, 2, usedAt = 100))
    dao.upsert(cache(8, 1, usedAt = 200))

    dao.upsertAndEvict(cache(9, 1, usedAt = 300), evictedTids = listOf(7))

    assertEquals(setOf(8L, 9L), dao.loadMeta().map { it.tid }.toSet())
  }

  @Test
  fun 已读按_uid_分桶_切号后互不污染() = runTest {
    val dao = db.notificationReadDao()
    dao.insertAll(listOf(NotificationReadEntity("1", "1786100000-2-1-1", 1)))
    dao.insertAll(listOf(NotificationReadEntity("2", "1786100000-2-9-9", 1)))

    assertEquals(listOf("1786100000-2-1-1"), dao.readIds("1"))
    assertEquals(listOf("1786100000-2-9-9"), dao.readIds("2"))

    dao.clearForUid("1")
    assertEquals(emptyList<String>(), dao.readIds("1"))
    assertEquals(1, dao.readIds("2").size)
  }

  @Test
  fun 重复标记已读是幂等的() = runTest {
    val dao = db.notificationReadDao()
    val row = NotificationReadEntity("1", "1786100000-2-1-1", 1)
    dao.insertAll(listOf(row))
    dao.insertAll(listOf(row.copy(readAt = 999)))

    assertEquals(1, dao.readIds("1").size)
  }
}
