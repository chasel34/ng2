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

/**
 * 移植自 `src/core/net/strategies/web-fallback.test.ts`(9 条)。
 *
 * 分两段:
 * - **链的性质**(四档档位、域名沿用、诊断记录、反解器缺席时让位)用一个假反解器
 *   ([FakeWebReadParser])钉,免得每条用例都拖着一整页 HTML;
 * - **换上真反解器之后的行为**(票 08)另起一段,语料取金样本 `web` domain 的
 *   `input.text` —— 真实抓包的整页 HTML 从传输层进来,一路解码、反解、成信封。
 *   `only` 档那两条(可重试的失败被改写成终点 / 本来就不可重试的原样上交)在这一段。
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

  // ── 反解器的注入点 ─────────────────────────────────────────────────────────

  @Test
  fun `反解器没接上时这一档直接让位,不白打一次网络请求`() = runTest {
    // 反解本体若因为 NGA 改版被临时摘掉,多打一次只是给 NGA 送一次限流计数
    val transport = nativeBlocked()

    val error = assertThrowsNga {
      chainClient(transport, WebFallbackMode.SECONDARY, UnavailableWebReadParser).execute(read)
    }

    assertEquals(NgaErrorKind.PARSE, error.kind, "最终错误仍是「被封」而不是「反解没接上」")
    assertEquals(listOf(false), transport.requests.map(::isWebRequest))
  }

  // ── 票 08:换上真反解器之后的链行为 ─────────────────────────────────────────

  /** 金样本 `web/<case>` 的 `input.text` —— Kotlin 侧 classpath 上只有金样本,没有 `.gbk.bin`。 */
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
    // 信封与 `__output=8` 同构,下游一行都不用改
    val data = result.data as JsonObject
    assertEquals(20, data.getValue("__R").jsonObject.size)
    assertEquals("测试测试zsbd", firstFloorContent(data))
  }

  @Test
  fun `only·真反解器解不出来时,错误被改写成不可重试`() = runTest {
    // 网页版也被封:拿回来的是一坨 HTML,反解不出任何楼层 → kind:parse(本来可重试)
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
    // 这一条是本用例的正主:`only` 说好了不碰原生接口,所以这一档失败就是终点
    assertEquals(false, error.retryable, "only 档的失败必须是不可重试的,链才会当场收手")
    assertEquals(listOf(true), transport.requests.map(::isWebRequest), "原生接口一次都没打")
  }

  @Test
  fun `only·本来就不可重试的服务端错误原样上交,不重新包一层`() = runTest {
    // msgcode 错误页 → kind:server、retryable=false,已经是终点,不该走改写那条路——
    // 改写会丢掉 `code`(错误页要拿它显示「2048 找不到主题」)
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
