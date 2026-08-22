package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.UnavailableWebReadParser
import com.chasel.ng2n.core.net.strategies.WebFallbackStrategy
import com.chasel.ng2n.core.net.strategies.WebReadParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 移植自 `src/core/net/strategies/web-fallback.test.ts`(9 条中的 7 条)。
 *
 * **本票只落策略壳与注入点,HTML 反解本体归票 08**,所以:
 * - 反解器换成一个假的([FakeWebReadParser]):它认「网页版那份响应」并吐一个与
 *   `__output=8` 同构的信封,足以钉住四档档位、域名沿用、`only` 档终点这些**链的性质**;
 * - 两条依赖真实 HTML 抓包的用例(「产物与 JSON 路线同构」里对正文的断言、
 *   「网页版返回 msgcode 错误」)留给票 08,见文末的 `未移植` 说明。
 */
class WebFallbackTest {

  private val okJson = """{"data":{"__R":{"0":{"content":"原生","lou":0}}},"time":1}"""
  private val webPageBody = "WEB-PAGE"

  /** 票 08 的反解器在这条链上的替身。 */
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

  /** 原生接口全被封、网页版还通 —— 这一档存在的理由就是这个局面。 */
  private fun nativeBlocked() = RecordingTransport { request ->
    if (isWebRequest(request)) FakeResponse(body = utf8(webPageBody)) else blocked()
  }

  /** 两条都通:用来看档位有没有真的改变「谁先上」。 */
  private fun bothWork() = RecordingTransport { request ->
    if (isWebRequest(request)) FakeResponse(body = utf8(webPageBody)) else ok(okJson)
  }

  @Test
  fun `secondary(默认)·原生先上,全垮了才反解,产物与 JSON 路线同构`() = runTest {
    val transport = nativeBlocked()

    val result = chainClient(transport, WebFallbackMode.SECONDARY).execute(read)

    assertEquals("web-fallback", result.via)
    assertEquals(listOf(false, true), transport.requests.map(::isWebRequest))
    // 反解出来的信封长得跟 `__output=8` 一样,所以下游认得出这是第几楼
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
    // 不带格式参数才是网页版
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

  // ── 票 08 的注入点 ─────────────────────────────────────────────────────────

  @Test
  fun `反解器没接上时这一档直接让位,不白打一次网络请求`() = runTest {
    // 占位实现在票 08 落地前一直是这个状态:多打一次只是给 NGA 送一次限流计数
    val transport = nativeBlocked()

    val error = assertThrowsNga {
      chainClient(transport, WebFallbackMode.SECONDARY, UnavailableWebReadParser).execute(read)
    }

    assertEquals(NgaErrorKind.PARSE, error.kind, "最终错误仍是「被封」而不是「反解没接上」")
    assertEquals(listOf(false), transport.requests.map(::isWebRequest))
  }
}
