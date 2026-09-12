package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.UnavailableWebReadParser
import com.chasel.ng2n.core.net.strategies.WebFallbackStrategy
import com.chasel.ng2n.core.net.strategies.WebReadParser
import com.chasel.ng2n.golden.Goldens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebFallbackTest {

  private val okJson = """{"data":{"__R":{"0":{"content":"原生","lou":0}}},"time":1}"""
  private val webPageBody = "WEB-PAGE"

  private class FakeWebReadParser(private val body: String) : WebReadParser {
    override val available = true
    override fun parse(text: String, via: String): NgaEnvelope {
      if (text != body) {
        throw NgaError(NgaErrorKind.PARSE, "这不是一张 read.php 网页", via = via)
      }
      return parseNgaJson("""{"data":{"__R":{"0":{"content":"网页","lou":0}}},"time":1}""", via)
    }
  }

  private fun chainClient(
    transport: Transport,
    mode: WebFallbackMode,
    parser: WebReadParser = FakeWebReadParser(webPageBody),
    comboCache: ComboCache = InMemoryComboCache(),
  ) = testClient(
    transport,
    strategies = listOf(
      WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY, parser),
      FormatRotationStrategy(listOf(ResponseFormat.JSON), listOf("https://bbs.nga.cn")),
      WebFallbackStrategy(WebFallbackStrategy.Placement.SECONDARY, parser),
    ),
    comboCache = comboCache,
    settings = FakeSettings(mode = mode),
  )

  private val read = readRequest("read.php", queryOf("tid" to 46186286, "page" to 1))

  private fun firstFloorContent(data: kotlinx.serialization.json.JsonElement?): String? =
    ((data as? JsonObject)?.get("__R") as? JsonObject)
      ?.get("0")?.jsonObject?.get("content")?.jsonPrimitive?.content

  private fun nativeBlocked() = RecordingTransport { request ->
    if (isWebRequest(request)) FakeResponse(body = utf8(webPageBody)) else blocked()
  }

  private fun bothWork() = RecordingTransport { request ->
    if (isWebRequest(request)) FakeResponse(body = utf8(webPageBody)) else ok(okJson)
  }

  @Test
  fun `secondary(默认)·原生先上,全垮了才反解,产物与 JSON 路线同构`() = runTest {
    val transport = nativeBlocked()

    val result = chainClient(transport, WebFallbackMode.SECONDARY).execute(read)

    assertEquals("web-fallback", result.via)
    assertEquals(listOf(false, true), transport.requests.map(::isWebRequest))
    assertEquals("网页", firstFloorContent(result.data))
  }

  @Test
  fun `primary·read_php 先反解,一次原生都不打`() = runTest {
    val transport = bothWork()

    val result = chainClient(transport, WebFallbackMode.PRIMARY).execute(read)

    assertEquals("web-fallback", result.via)
    assertEquals(listOf(true), transport.requests.map(::isWebRequest))
  }

  @Test
  fun `primary·反解不出来还能退回原生`() = runTest {
    val transport = RecordingTransport { request ->
      if (isWebRequest(request)) blocked() else ok(okJson)
    }

    val result = chainClient(transport, WebFallbackMode.PRIMARY).execute(read)

    assertEquals("format-rotation", result.via)
    assertEquals(listOf(true, false), transport.requests.map(::isWebRequest))
  }

  @Test
  fun `only·反解垮了就是终点,不再退回原生`() = runTest {
    val transport = RecordingTransport { request ->
      if (isWebRequest(request)) blocked() else ok(okJson)
    }

    val error = assertThrowsNga { chainClient(transport, WebFallbackMode.ONLY).execute(read) }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("web-fallback", error.via)
    assertEquals(listOf(true), transport.requests.map(::isWebRequest))
  }

  @Test
  fun `disabled·两个位置都不启用,链上当这一档不存在`() = runTest {
    val transport = nativeBlocked()

    val error = assertThrowsNga { chainClient(transport, WebFallbackMode.DISABLED).execute(read) }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(listOf(false), transport.requests.map(::isWebRequest))
  }

  @Test
  fun `别的接口一律跳过——only 也一样,那档说的是 read_php 只走反解`() = runTest {
    val transport = bothWork()

    val result = chainClient(transport, WebFallbackMode.ONLY)
      .execute(readRequest("thread.php", queryOf("fid" to 7)))

    assertEquals("format-rotation", result.via)
    assertEquals(listOf(false), transport.requests.map(::isWebRequest))
  }

  @Test
  fun `域名沿用缓存里试通的那个·这一档换的是格式,不是域名`() = runTest {
    val cache = InMemoryComboCache()
    cache.remember("read.php", FetchCombo(ResponseFormat.JSON, "https://ngabbs.com"))
    val transport = bothWork()
    val client = testClient(
      transport,
      strategies = listOf(
        WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY, FakeWebReadParser(webPageBody)),
      ),
      comboCache = cache,
      settings = FakeSettings(mode = WebFallbackMode.ONLY),
    )

    client.execute(read)

    assertTrue(transport.requests[0].url.startsWith("https://ngabbs.com/read.php"))
    assertTrue(!transport.requests[0].url.contains("__output="))
  }

  @Test
  fun `诊断记录里这一档的格式档位是 html,排障时一眼看得出走了哪条路`() = runTest {
    val transport = RecordingTransport { blocked() }
    val client = testClient(
      transport,
      strategies = listOf(
        WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY, FakeWebReadParser(webPageBody)),
      ),
      settings = FakeSettings(mode = WebFallbackMode.ONLY),
    )

    val error = assertThrowsNga { client.execute(read) }

    assertEquals("web-fallback", error.diagnostic?.attempts?.single()?.strategy)
    assertEquals("html", error.diagnostic?.attempts?.single()?.format)
  }

  @Test
  fun `反解器没接上时这一档直接让位,不白打一次网络请求`() = runTest {
    val transport = nativeBlocked()

    val error = assertThrowsNga {
      chainClient(transport, WebFallbackMode.SECONDARY, UnavailableWebReadParser).execute(read)
    }

    assertEquals(NgaErrorKind.PARSE, error.kind, "最终错误仍是「被封」而不是「反解没接上」")
    assertEquals(listOf(false), transport.requests.map(::isWebRequest))
  }

  private fun webGolden(case: String): String =
    Goldens.load("web").first { it.name == case }.stringField("text")

  private fun htmlPage(body: String) =
    FakeResponse(contentType = "text/html; charset=UTF-8", body = utf8(body))

  @Test
  fun `真反解器·原生全被封时网页版把这一页整个救回来`() = runTest {
    val page = webGolden("revalidate-45150945")
    val transport = RecordingTransport { request ->
      if (isWebRequest(request)) htmlPage(page) else blocked()
    }

    val result = testClient(
      transport,
      strategies = listOf(
        FormatRotationStrategy(listOf(ResponseFormat.JSON), listOf("https://bbs.nga.cn")),
        WebFallbackStrategy(WebFallbackStrategy.Placement.SECONDARY),
      ),
      settings = FakeSettings(mode = WebFallbackMode.SECONDARY),
    ).execute(read)

    assertEquals("web-fallback", result.via)
    val data = result.data as JsonObject
    assertEquals(20, data.getValue("__R").jsonObject.size)
    assertEquals("测试测试zsbd", firstFloorContent(data))
  }

  @Test
  fun `only·真反解器解不出来时,错误被改写成不可重试`() = runTest {
    val transport = RecordingTransport { request ->
      if (isWebRequest(request)) blocked() else ok(okJson)
    }
    val client = testClient(
      transport,
      strategies = listOf(
        WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY),
        FormatRotationStrategy(listOf(ResponseFormat.JSON), listOf("https://bbs.nga.cn")),
      ),
      settings = FakeSettings(mode = WebFallbackMode.ONLY),
    )

    val error = assertThrowsNga { client.execute(read) }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("web-fallback", error.via)
    assertEquals(false, error.retryable, "only 档的失败必须是不可重试的,链才会当场收手")
    assertEquals(listOf(true), transport.requests.map(::isWebRequest), "原生接口一次都没打")
  }

  @Test
  fun `only·本来就不可重试的服务端错误原样上交,不重新包一层`() = runTest {
    val transport = RecordingTransport { htmlPage(webGolden("not-found")) }
    val client = testClient(
      transport,
      strategies = listOf(WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY)),
      settings = FakeSettings(mode = WebFallbackMode.ONLY),
    )

    val error = assertThrowsNga { client.execute(read) }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals(false, error.retryable)
    assertEquals("2048", error.code?.content)
    assertEquals("2048:找不到主题", error.text)
  }

  @Test
  fun `secondary·反解不出来时错误照旧可重试,链上后面几档还轮得到`() = runTest {
    val transport = RecordingTransport { blocked() }
    val cache = StubStrategy("topic-cache")
    val client = testClient(
      transport,
      strategies = listOf(
        WebFallbackStrategy(WebFallbackStrategy.Placement.SECONDARY),
        cache,
      ),
      settings = FakeSettings(mode = WebFallbackMode.SECONDARY),
    )

    val result = client.execute(read)

    assertEquals("topic-cache", result.via, "不是 only 档,反解失败之后帖子缓存要轮得到")
    assertEquals(1, cache.calls)
  }
}
