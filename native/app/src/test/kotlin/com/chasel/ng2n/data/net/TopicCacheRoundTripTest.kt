package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.api.ApiGoldenJson
import com.chasel.ng2n.core.api.TopicPageSnapshot
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.api.fetchTopicDetail
import com.chasel.ng2n.core.net.FakeTopicCacheStore
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.Transport
import com.chasel.ng2n.core.net.assertThrowsNga
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheStrategy
import com.chasel.ng2n.core.net.testClient
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.db.TopicCacheDao
import com.chasel.ng2n.data.db.TopicCacheEntity
import com.chasel.ng2n.data.db.TopicCacheMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 帖子缓存载荷的端到端(票 07 把 `TopicCachePayloadReader` 接实的那一半):
 * 在线拉一页 → 攒下的信封进缓存 → 断网时同一页从缓存还回来。
 *
 * 两趟走的是**同一个** `fetchTopicDetail`,差别只在链上哪一档产出了信封——
 * 这正是「信封天然同构」这条设计的验收(手工移植自 `topic-detail.cache.test.ts`)。
 */
class TopicCacheRoundTripTest {

  private val tid = 44191387L

  /** 在线那一趟。 */
  private fun onlineClient() = testClient(RecordingTransport { ok(READ_PAGE) })

  /** 断网那一趟:链上只剩缓存档,一个字节都发不出去。 */
  private fun offlineClient(store: FakeTopicCacheStore) = testClient(
    Transport { throw IOException("断网") },
    strategies = listOf(TopicCacheStrategy(store)),
  )

  @Test
  fun `拿到一页就交出可缓存的快照——元数据加可还原的信封`() = runTest {
    val snapshots = mutableListOf<TopicPageSnapshot>()
    val detail = fetchTopicDetail(
      onlineClient(),
      tid = tid,
      page = 1,
      favCode = "abc123",
      onSnapshot = { snapshots += it },
    )

    assertEquals(1, snapshots.size)
    val snapshot = snapshots.single()
    assertEquals(tid, snapshot.tid)
    assertEquals(1, snapshot.page)
    assertEquals(detail.subject, snapshot.subject)
    assertEquals(detail.boardName, snapshot.boardName)
    assertEquals(detail.floors.size, snapshot.floors)
    assertEquals(detail.totalPages, snapshot.totalPages)
    assertEquals("abc123", snapshot.favCode)
    assertTrue(snapshot.payload.startsWith("{"), "payload 应当是序列化后的信封")
  }

  @Test
  fun `前台阅读可以把快照序列化延迟到转场结束后`() = runTest {
    var create: (() -> TopicPageSnapshot)? = null
    val detail = fetchTopicDetail(
      onlineClient(),
      tid = tid,
      page = 1,
      deferSnapshot = { create = it },
    )

    val make = assertNotNull(create)
    val snapshot = make()
    assertEquals(detail.subject, snapshot.subject)
    assertEquals(detail.floors.size, snapshot.floors)
  }

  @Test
  fun `只看该楼 只看某人是过滤视图,不写缓存`() = runTest {
    val snapshots = mutableListOf<TopicPageSnapshot>()
    fetchTopicDetail(onlineClient(), tid = tid, page = 1, pid = 9, onSnapshot = { snapshots += it })
    fetchTopicDetail(
      onlineClient(),
      tid = tid,
      page = 1,
      authorId = 205511,
      onSnapshot = { snapshots += it },
    )
    assertEquals(emptyList(), snapshots)
  }

  @Test
  fun `缓存还回来的一页与在线那一页除来源外完全一致`() = runTest {
    val store = FakeTopicCacheStore()
    val online = fetchTopicDetail(
      onlineClient(),
      tid = tid,
      page = 1,
      onSnapshot = { store.put(it.tid, it.page, it.payload) },
    )
    assertEquals(TopicSource.NATIVE, online.source)

    val restored = fetchTopicDetail(offlineClient(store), tid = tid, page = 1)
    assertEquals(TopicSource.CACHE, restored.source)
    assertEquals(
      normalizeContext(online),
      normalizeContext(restored.copy(source = TopicSource.NATIVE)),
    )
  }

  @Test
  fun `缓存档出的结果不再回写一遍(内容一模一样)`() = runTest {
    val store = FakeTopicCacheStore()
    fetchTopicDetail(
      onlineClient(),
      tid = tid,
      page = 1,
      onSnapshot = { store.put(it.tid, it.page, it.payload) },
    )

    val snapshots = mutableListOf<TopicPageSnapshot>()
    fetchTopicDetail(offlineClient(store), tid = tid, page = 1, onSnapshot = { snapshots += it })
    assertEquals(emptyList(), snapshots)
  }

  @Test
  fun `缓存里没有这一页时,断网就是断网`() = runTest {
    val store = FakeTopicCacheStore()
    assertThrowsNga { fetchTopicDetail(offlineClient(store), tid = tid, page = 9) }
    assertEquals(listOf(tid to 9), store.reads.map { it.tid to it.page })
  }

  // ── 存储侧的接缝:快照 → Room 行 → 还回来的 payload ────────────────────────

  @Test
  fun `快照经 TopicCachePayloadReader 落库,再从同一个口读回来`() = runTest {
    val dao = InMemoryTopicCacheDao()
    val reader = TopicCachePayloadReader(TopicCacheRepository(dao))

    var captured: TopicPageSnapshot? = null
    fetchTopicDetail(onlineClient(), tid = tid, page = 1, onSnapshot = { captured = it })
    val snapshot = assertNotNull(captured)
    reader.save(snapshot)

    assertEquals(snapshot.payload, reader.read(TopicCacheKey(tid, 1)))
    assertNull(reader.read(TopicCacheKey(tid, 2)))
    // 元数据也照抄过去了(「我的缓存」列表要显示它们)
    val meta = dao.loadMeta().single()
    assertEquals(snapshot.subject, meta.subject)
    assertEquals(snapshot.floors, meta.floors)
  }
}

/** 匿名楼层的用户 key 带请求级前缀(每次请求换一个),比对前统一抹掉。 */
private fun normalizeContext(detail: com.chasel.ng2n.core.api.TopicDetail): String =
  ApiGoldenJson.encodeToJsonElement(detail).toString()
    .replace(Regex("[a-z0-9]+\\.[a-z0-9]+,-"), "ctx,-")

/** 带一个匿名楼层的真实形状(匿名 key 前缀必须在两趟之间被抹掉才比得了)。 */
private const val READ_PAGE =
  """{"data":{"__GLOBAL":{"_ATTACH_BASE_VIEW":"img.nga.cn/attachments"},
  "__T":{"tid":44191387,"subject":"测试主题","author":"nga_user","authorid":10000001},
  "__F":{"name":"网事杂谈"},
  "__U":{"10000001":{"uid":10000001,"username":"nga_user","rvrc":15},
         "-1":{"uid":0,"username":"#anony_0123456789abcdef0123456789abcdef"}},
  "__R":{"0":{"pid":0,"lou":0,"authorid":10000001,"content":"主楼","postdate":"2025-09-18 23:55"},
         "1":{"pid":1,"lou":1,"authorid":-1,"content":"匿名回复","postdate":"2025-09-18 23:56"}},
  "__ROWS":2,"__R__ROWS_PAGE":20},"time":1}"""

/** 内存版 DAO:票 14 的 Room 实现由 `androidTest` 的 `Ng2nDatabaseTest` 盯着,这里只验搬运。 */
private class InMemoryTopicCacheDao : TopicCacheDao {

  private val rows = LinkedHashMap<Pair<Long, Int>, TopicCacheEntity>()

  override fun observeMeta(): Flow<List<TopicCacheMeta>> = flowOf(metaList())

  override suspend fun loadMeta(): List<TopicCacheMeta> = metaList()

  override suspend fun readPayload(tid: Long, page: Int): String? = rows[tid to page]?.payload

  override suspend fun upsert(entry: TopicCacheEntity) {
    rows[entry.tid to entry.page] = entry
  }

  override suspend fun touch(tid: Long, usedAt: Long) {
    for ((key, value) in rows) if (key.first == tid) rows[key] = value.copy(usedAt = usedAt)
  }

  override suspend fun deleteTopics(tids: List<Long>) {
    rows.keys.removeAll { it.first in tids }
  }

  override suspend fun clear() = rows.clear()

  private fun metaList(): List<TopicCacheMeta> = rows.values.map {
    TopicCacheMeta(
      tid = it.tid,
      page = it.page,
      subject = it.subject,
      boardName = it.boardName,
      favCode = it.favCode,
      floors = it.floors,
      totalPages = it.totalPages,
      bytes = it.bytes,
      usedAt = it.usedAt,
    )
  }
}
