package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.ai.AiSource
import com.chasel.ng2n.core.ai.checkExternalUrl
import com.chasel.ng2n.core.ai.TopicContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.Dns
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun forumSession(): ForumToolSession = ForumToolSession(
  TopicContext(listOf(AiSource("s1", 1, 10, 0, 1, "楼主", "2026-09-01", "主楼正文", emptyList(), "floor")),
    null, 0, listOf(1), title = "主题"),
  read = { error("本测试不读取论坛") }, rules = { emptyList() })

private fun page(title: String, body: String) =
  "<html><head><title>$title</title></head><body><p>$body</p></body></html>"

class WebToolSessionTest {

  @Test
  fun `搜索注册来源并标记未读正文`() = runTest {
    val forum = forumSession()
    val session = WebToolSession(forum, fetch = { url, _ -> WebFetchResult(200, url, liteResults) })
    val result = session.execute("search_web", WebToolArgs(query = "月度数据"))
    assertEquals("ok", result.getValue("status").jsonPrimitive.content)
    val results = result.getValue("results").jsonArray
    assertEquals(10, results.size)
    assertEquals(10, result.getValue("count").jsonPrimitive.int)
    assertEquals("s2", results[0].jsonObject.getValue("sourceId").jsonPrimitive.content)
    assertEquals(false, results[0].jsonObject.getValue("bodyRead").jsonPrimitive.boolean)
    val source = forum.allSources.single { it.id == "s2" }
    assertEquals("https://www.stats.gov.cn/sj/fbrc/202412/t20241230_1958031.html", source.web?.url)
    assertEquals(false, source.web?.bodyRead)
  }

  @Test
  fun `验证页归为 challenge 且不是零结果`() = runTest {
    val session = WebToolSession(forumSession(), fetch = { url, _ -> WebFetchResult(200, url, "<html>bots use duckduckgo too</html>") })
    val result = session.execute("search_web", WebToolArgs(query = "出口 统计"))
    assertEquals("challenge", result.getValue("status").jsonPrimitive.content)
    assertTrue("人机验证" in result.getValue("detail").jsonPrimitive.content)
  }

  @Test
  fun `零结果是成功状态`() = runTest {
    val session = WebToolSession(forumSession(), fetch = { url, _ -> WebFetchResult(200, url, "<html><body>No results found for &quot;x&quot;</body></html>") })
    val result = session.execute("search_web", WebToolArgs(query = "zzqqxx"))
    assertEquals("ok", result.getValue("status").jsonPrimitive.content)
    assertEquals(0, result.getValue("count").jsonPrimitive.int)
  }

  @Test
  fun `网络失败与零结果分开`() = runTest {
    val session = WebToolSession(forumSession(), fetch = { _, _ -> throw java.io.IOException("断网") })
    val result = session.execute("search_web", WebToolArgs(query = "月度数据"))
    assertEquals("request_failed", result.getValue("status").jsonPrimitive.content)
  }

  @Test
  fun `读取正文升级同一条来源并可续读`() = runTest {
    val forum = forumSession()
    val body = "正".repeat(20_000)
    val session = WebToolSession(forum, fetch = { url, _ ->
      WebFetchResult(200, url, if ("lite" in url) liteResults else page("月度数据发布", body))
    })
    session.execute("search_web", WebToolArgs(query = "月度数据"))
    val read = session.execute("read_webpage", WebToolArgs(url = "https://www.stats.gov.cn/sj/fbrc/202412/t20241230_1958031.html"))
    assertEquals("ok", read.getValue("status").jsonPrimitive.content)
    assertEquals("s2", read.getValue("sourceId").jsonPrimitive.content)
    assertEquals(16000, read.getValue("nextOffset").jsonPrimitive.int)
    assertEquals(20_000, read.getValue("totalCharacters").jsonPrimitive.int)
    assertEquals(16000, read.getValue("material").jsonPrimitive.content.length)
    val source = forum.allSources.single { it.id == "s2" }
    assertEquals(true, source.web?.bodyRead)
    assertEquals("月度数据发布", source.web?.title)
    assertEquals(10, forum.allSources.count { it.web != null })
  }

  @Test
  fun `短期缓存复用已读网页`() = runTest {
    var calls = 0
    val session = WebToolSession(forumSession(), fetch = { url, _ -> calls++; WebFetchResult(200, url, page("标题", "正文")) })
    session.execute("read_webpage", WebToolArgs(url = "https://example.com/a"))
    val second = session.execute("read_webpage", WebToolArgs(url = "https://example.com/a"))
    assertEquals(1, calls)
    assertEquals(true, second.getValue("cached").jsonPrimitive.boolean)
  }

  @Test
  fun `服务端错误重试一次后成功`() = runTest {
    var calls = 0
    val session = WebToolSession(forumSession(), fetch = { url, _ ->
      calls++
      if (calls == 1) WebFetchResult(503, url, "") else WebFetchResult(200, url, page("标题", "正文"))
    })
    assertEquals("ok", session.execute("read_webpage", WebToolArgs(url = "https://example.com/a")).getValue("status").jsonPrimitive.content)
    assertEquals(2, calls)
  }

  @Test
  fun `URL 策略在发起请求前拒绝私网与非 http 地址`() = runTest {
    var calls = 0
    val session = WebToolSession(forumSession(), fetch = { url, _ -> calls++; WebFetchResult(200, url, page("标题", "正文")) })
    listOf("http://127.0.0.1/x", "http://localhost:8080/x", "http://192.168.1.1/x",
      "http://169.254.169.254/latest/meta-data", "file:///etc/hosts", "ftp://example.com/a")
      .forEach { url ->
        val result = session.execute("read_webpage", WebToolArgs(url = url))
        assertEquals("invalid_parameters", result.getValue("status").jsonPrimitive.content, url)
      }
    assertEquals(0, calls)
  }

  @Test
  fun `搜索用表单 POST 发起`() = runTest {
    var seen: Pair<String, Map<String, String>?>? = null
    val session = WebToolSession(forumSession(), fetch = { url, form -> seen = url to form; WebFetchResult(200, url, liteResults) })
    session.execute("search_web", WebToolArgs(query = "月度数据"))
    assertEquals("https://lite.duckduckgo.com/lite/", seen!!.first)
    assertEquals(mapOf("q" to "月度数据"), seen!!.second)
  }

  @Test
  fun `超出单次上限的结果单独说明且不分配来源`() = runTest {
    val many = (1..14).joinToString("") { index ->
      """<tr><td><a rel="nofollow" href="https://example.com/$index" class='result-link'>结果 $index</a></td></tr>""" +
        """<tr><td class='result-snippet'>摘要 $index</td></tr>"""
    }
    val session = WebToolSession(forumSession(), fetch = { url, _ -> WebFetchResult(200, url, "<table>$many</table>") })
    val result = session.execute("search_web", WebToolArgs(query = "很多结果"))
    assertEquals(10, result.getValue("count").jsonPrimitive.int)
    assertEquals(10, result.getValue("results").jsonArray.size)
    assertEquals(4, result.getValue("omitted").jsonPrimitive.int)
    assertTrue("4 条超出单次上限未返回" in result.getValue("detail").jsonPrimitive.content)
  }

  @Test
  fun `正文超过上限时标记未读完`() = runTest {
    val forum = forumSession()
    val session = WebToolSession(forum, fetch = { url, _ -> WebFetchResult(200, url, page("长页", "字".repeat(70_000))) })
    val result = session.execute("read_webpage", WebToolArgs(url = "https://example.com/long"))
    assertEquals(true, result.getValue("truncated").jsonPrimitive.boolean)
    assertEquals(60_000, result.getValue("totalCharacters").jsonPrimitive.int)
    assertTrue("未读完" in result.getValue("detail").jsonPrimitive.content)
    assertTrue("不能声称已读全文" in result.getValue("scope").jsonPrimitive.content)
    assertEquals(true, forum.allSources.single { it.web != null }.web?.truncated)
  }

  @Test
  fun `字节上限触发时同样标记未读完`() = runTest {
    val session = WebToolSession(forumSession(), fetch = { url, _ -> WebFetchResult(200, url, page("短页", "正文"), truncated = true) })
    assertEquals(true, session.execute("read_webpage", WebToolArgs(url = "https://example.com/a"))
      .getValue("truncated").jsonPrimitive.boolean)
  }

  @Test
  fun `部分搜索失败不触发通知条`() {
    val failed = ToolCallRow("a", "search_web", "q1", "challenge", "人机验证")
    val ok = ToolCallRow("b", "search_web", "q2", "ok", "2 条结果")
    val read = ToolCallRow("c", "read_webpage", "example.com", "ok", "正文 100 字", "s2")
    assertTrue(webSearchUnavailable(listOf(failed)))
    assertFalse(webSearchUnavailable(listOf(failed, ok)))
    assertFalse(webSearchUnavailable(listOf(failed, read)))
    assertFalse(webSearchUnavailable(listOf(ok, read)))
  }

  @Test
  fun `未知参数按参数错误处理`() = runTest {
    val session = WebToolSession(forumSession(), fetch = { _, _ -> error("不应发起请求") })
    assertEquals("invalid_parameters", session.execute("search_web", WebToolArgs(query = " ")).getValue("status").jsonPrimitive.content)
    assertEquals("invalid_parameters", session.execute("read_webpage", WebToolArgs(url = "https://example.com", offset = -1)).getValue("status").jsonPrimitive.content)
  }

  private val liteResults: String get() = checkNotNull(
    WebToolSessionTest::class.java.classLoader?.getResourceAsStream("fixtures/web/ddg-lite-results.html"))
    .use { it.readBytes() }.toString(Charsets.UTF_8)
}

class WebReaderTest {

  private lateinit var server: MockWebServer

  @BeforeTest fun setUp() { server = MockWebServer(); server.start() }
  @AfterTest fun tearDown() { server.close() }

  @Test
  fun `站外请求不携带任何 Cookie`() = runTest {
    server.enqueue(MockResponse.Builder().code(200)
      .addHeader("Set-Cookie", "ngaPassportUid=12345; Path=/")
      .addHeader("Content-Type", "text/html; charset=utf-8").body("<html><body>一</body></html>").build())
    server.enqueue(MockResponse.Builder().code(200)
      .addHeader("Content-Type", "text/html; charset=utf-8").body("<html><body>二</body></html>").build())
    val reader = WebReader(loopbackClient, policy = { null })
    reader.fetch(server.url("/a").toString())
    reader.fetch(server.url("/b").toString())
    assertNull(server.takeRequest().headers["Cookie"])
    assertNull(server.takeRequest().headers["Cookie"])
    assertEquals(CookieJar.NO_COOKIES, WebReader.shared.cookieJar)
  }

  @Test
  fun `重定向逐跳走 URL 策略`() = runTest {
    server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:1/private").build())
    val first = server.url("/a").toString()
    val reader = WebReader(loopbackClient, policy = { url -> if (url == first) null else checkExternalUrl(url) })
    assertFailsWith<WebFetchRejected> { reader.fetch(first) }
  }

  @Test
  fun `私网解析结果被 DNS 拒绝`() {
    assertFailsWith<UnknownHostException> { PublicOnlyDns.lookup("localhost") }
  }

  private val loopbackClient get() = WebReader.shared.newBuilder().dns(Dns.SYSTEM).build()
}
