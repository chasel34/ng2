package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.TOPIC_CACHE_STRATEGY_NAME
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.core.net.strategies.TopicCacheStrategy
import com.chasel.ng2n.core.net.strategies.topicCacheKeyOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicCacheStrategyTest {

  private val pageJson = """{"data":{"__R":{"0":{"content":"第一楼","lou":0}},"__ROWS":1},"time":1}"""

  private val readRequestWithPage =
    readRequest("read.php", queryOf("tid" to 45150945, "page" to 2, "v2" to 1))

  private fun chainClient(transport: Transport, store: TopicCacheReader) = testClient(
    transport,
    strategies = listOf(FormatRotationStrategy(), TopicCacheStrategy(store)),
  )

  @Test
  fun `整帖阅读的 read_php 认得出 tid 与页码`() {
    assertEquals(TopicCacheKey(45150945, 2), topicCacheKeyOf(readRequestWithPage))
  }

  @Test
  fun `没写页码就是第 1 页`() {
    assertEquals(
      TopicCacheKey(7, 1),
      topicCacheKeyOf(readRequest("read.php", queryOf("tid" to 7))),
    )
  }

  @Test
  fun `fav 码不影响缓存身份·带不带码看到的是同一页内容`() {
    assertEquals(
      TopicCacheKey(7, 1),
      topicCacheKeyOf(readRequest("read.php", queryOf("tid" to 7, "page" to 1, "fav" to "abc"))),
    )
  }

  @Test
  fun `只看该楼与只看某人是过滤视图,不缓存`() {
    assertNull(topicCacheKeyOf(readRequest("read.php", queryOf("tid" to 7, "pid" to 99))))
    assertNull(topicCacheKeyOf(readRequest("read.php", queryOf("tid" to 7, "authorid" to 5))))
  }

  @Test
  fun `别的接口没有缓存`() {
    assertNull(topicCacheKeyOf(readRequest("thread.php", queryOf("fid" to 7))))
    assertNull(topicCacheKeyOf(readRequest("read.php")))
  }

  @Test
  fun `前面全败、缓存命中时返回缓存数据`() = runTest {
    val store = FakeTopicCacheStore()
    store.put(45150945, 2, pageJson)

    val result = chainClient(RecordingTransport { blocked() }, store).execute(readRequestWithPage)

    assertEquals(TOPIC_CACHE_STRATEGY_NAME, result.via)
    assertEquals(parseNgaJson(pageJson).data, result.data)
  }

  @Test
  fun `缓存没命中时不顶替真正的失败原因,错误仍是被封那条`() = runTest {
    val store = FakeTopicCacheStore()

    val error = assertThrowsNga {
      chainClient(RecordingTransport { blocked() }, store).execute(readRequestWithPage)
    }

    assertTrue(error.kind != NgaErrorKind.UNAVAILABLE)
    assertTrue(error.via != TOPIC_CACHE_STRATEGY_NAME)
    assertEquals(listOf(TopicCacheKey(45150945, 2)), store.reads)
  }

  @Test
  fun `网络这条路通的时候根本不碰缓存`() = runTest {
    val store = FakeTopicCacheStore()
    store.put(45150945, 2, """{"data":{"__R":{},"__ROWS":0}}""")
    val transport = RecordingTransport { ok(pageJson) }

    val result = chainClient(transport, store).execute(readRequestWithPage)

    assertTrue(result.via != TOPIC_CACHE_STRATEGY_NAME)
    assertEquals(1, transport.requests.size)
    assertEquals(0, store.reads.size)
  }

  @Test
  fun `过滤视图(只看该楼)不读缓存,断网就是断网`() = runTest {
    val store = FakeTopicCacheStore()
    store.put(45150945, 1, pageJson)

    assertThrowsNga {
      chainClient(RecordingTransport { blocked() }, store)
        .execute(readRequest("read.php", queryOf("tid" to 45150945, "pid" to 12345)))
    }

    assertEquals(0, store.reads.size)
  }

  @Test
  fun `缓存里的内容坏了就当没缓存,不把解析异常抛给调用方`() = runTest {
    val store = TopicCacheReader { "这不是 JSON" }

    val error = assertThrowsNga {
      chainClient(RecordingTransport { blocked() }, store).execute(readRequestWithPage)
    }

    assertTrue(error.via != TOPIC_CACHE_STRATEGY_NAME)
  }

  @Test
  fun `本地库抛异常也只当这一档不适用`() = runTest {
    val store = TopicCacheReader { throw IllegalStateException("库被锁了") }

    val error = assertThrowsNga {
      chainClient(RecordingTransport { blocked() }, store).execute(readRequestWithPage)
    }

    assertEquals(NgaErrorKind.PARSE, error.kind, "最终错误仍是「被封」")
  }
}
