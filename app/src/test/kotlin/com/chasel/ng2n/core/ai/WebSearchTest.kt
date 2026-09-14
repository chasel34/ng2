package com.chasel.ng2n.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String {
  val loader = WebSearchTest::class.java.classLoader ?: throw AssertionError("拿不到 classloader")
  val path = "fixtures/web/$name"
  return (loader.getResourceAsStream(path) ?: throw AssertionError("classpath 上找不到 $path"))
    .use { it.readBytes() }.toString(Charsets.UTF_8)
}

class WebSearchTest {

  // 三份 fixture 是 2026-09-14 抓取的真实 DuckDuckGo Lite 响应，只替换了 vqd 与验证页的一次性令牌。
  @Test
  fun `保存的 Lite 样本解析出标题链接与摘要`() {
    val page = parseDuckDuckGoLite(fixture("ddg-lite-results.html"))
    assertEquals(WEB_SEARCH_OK, page.status)
    assertEquals(10, page.hits.size)
    assertEquals("社会消费品零售总额月度报告 - 国家统计局", page.hits[0].title)
    assertEquals("https://www.stats.gov.cn/sj/fbrc/202412/t20241230_1958031.html", page.hits[0].url)
    assertTrue(page.hits.all { it.url.startsWith("https://") || it.url.startsWith("http://") })
    assertTrue(page.hits.all { it.title.isNotBlank() })
    assertTrue(page.hits.count { it.snippet.isNotBlank() } >= 9)
    assertEquals(page.hits.size, page.hits.distinctBy { it.url }.size)
  }

  @Test
  fun `重定向式 uddg 链接仍然解码`() {
    val html = """<table><tr><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.stats.gov.cn%2Fsj%2F&amp;rut=9f1" class='result-link'>标题</a></td></tr>""" +
      """<tr><td class='result-snippet'>摘要&hellip;</td></tr></table>"""
    val page = parseDuckDuckGoLite(html)
    assertEquals("https://www.stats.gov.cn/sj/", page.hits.single().url)
    assertEquals("摘要…", page.hits.single().snippet)
  }

  @Test
  fun `结果里的私网链接被丢弃`() {
    val html = """<table><tr><td><a rel="nofollow" href="http://127.0.0.1:8080/internal" class='result-link'>本机调试页</a></td></tr>""" +
      """<tr><td class='result-snippet'>不应出现。</td></tr>""" +
      """<tr><td><a rel="nofollow" href="https://example.com/a" class='result-link'>公网页</a></td></tr></table>"""
    assertEquals(listOf("https://example.com/a"), parseDuckDuckGoLite(html).hits.map { it.url })
  }

  @Test
  fun `验证页归为 challenge 而不是零结果`() {
    val page = parseDuckDuckGoLite(fixture("ddg-lite-challenge.html"))
    assertEquals(WEB_SEARCH_CHALLENGE, page.status)
    assertTrue(page.hits.isEmpty())
  }

  @Test
  fun `空结果页归为零结果`() {
    val page = parseDuckDuckGoLite(fixture("ddg-lite-empty.html"))
    assertEquals(WEB_SEARCH_OK, page.status)
    assertTrue(page.hits.isEmpty())
  }

  @Test
  fun `结构未知的页面不当作零结果`() {
    assertEquals(WEB_SEARCH_CHALLENGE, parseDuckDuckGoLite("<html><body>service unavailable</body></html>").status)
  }

  @Test
  fun `真实验证页标记齐全`() {
    val html = fixture("ddg-lite-challenge.html")
    assertTrue("anomaly-modal" in html)
    assertTrue("result-link" !in html)
  }

  @Test
  fun `网页正文去掉脚本样式并解码实体`() {
    val html = """
      <html><head><title>月度数据 &amp; 说明</title><style>.a{color:red}</style></head>
      <body><script>var x = '<p>不是正文</p>';</script>
      <h1>标题</h1><p>第一段&nbsp;正文。</p><div>第二段</div><!-- 注释 --></body></html>
    """.trimIndent()
    assertEquals("月度数据 & 说明", webPageTitle(html))
    val page = extractWebPage(html)
    assertTrue("不是正文" !in page.text)
    assertTrue("color:red" !in page.text)
    assertFalse(page.truncated)
    assertEquals(listOf("标题", "第一段 正文。", "第二段"), page.text.lines())
  }

  @Test
  fun `正文超过上限时截断并标记`() {
    val page = extractWebPage("<p>" + "字".repeat(50) + "</p>", limit = 10)
    assertEquals(10, page.text.length)
    assertTrue(page.truncated)
    assertFalse(extractWebPage("<p>" + "字".repeat(10) + "</p>", limit = 10).truncated)
  }
}

class WebUrlPolicyTest {

  @Test
  fun `拒绝非 http 协议`() {
    listOf("file:///etc/hosts", "ftp://example.com/a", "javascript:alert(1)", "content://media/1")
      .forEach { assertNotNull(checkExternalUrl(it), it) }
  }

  @Test
  fun `拒绝本机与私网地址`() {
    listOf("http://localhost:8080/x", "http://LOCALHOST/x", "http://a.localhost/x", "http://printer.local/x",
      "http://svc.internal/x", "http://127.0.0.1/x", "http://10.1.2.3/x", "http://172.16.0.1/x",
      "http://172.31.255.254/x", "http://192.168.1.1/x", "http://169.254.169.254/latest/meta-data",
      "http://100.64.0.1/x", "http://0.0.0.0/x", "http://[::1]/x", "http://[fd00::1]/x", "http://[fe80::1]/x",
      "http://[::ffff:127.0.0.1]/x")
      .forEach { assertNotNull(checkExternalUrl(it), it) }
  }

  @Test
  fun `放行公网地址`() {
    listOf("https://www.stats.gov.cn/sj/", "http://example.com", "https://8.8.8.8/x", "https://[2001:4860:4860::8888]/x",
      "https://172.32.0.1/x", "https://192.169.0.1/x")
      .forEach { assertNull(checkExternalUrl(it), it) }
  }

  @Test
  fun `拒绝带账号信息的地址`() {
    assertNotNull(checkExternalUrl("https://user:pass@example.com/x"))
  }

  @Test
  fun `按解析结果判定私网地址`() {
    assertTrue(isPrivateAddress(byteArrayOf(127, 0, 0, 1)))
    assertTrue(isPrivateAddress(byteArrayOf(10, 0, 0, 1)))
    assertTrue(isPrivateAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 1)))
    assertTrue(!isPrivateAddress(byteArrayOf(8, 8, 8, 8)))
    assertTrue(isPrivateAddress(ByteArray(16).also { it[15] = 1 }))
  }

  @Test
  fun `域名取主机名并去掉 www`() {
    assertEquals("stats.gov.cn", webDomain("https://www.stats.gov.cn/sj/zxfb/"))
    assertEquals("customs.gov.cn", webDomain("http://customs.gov.cn"))
  }
}

class AnswerClaimTest {

  @Test
  fun `说法块按状态与序号解析`() {
    val blocks = splitAnswerBlocks(
      """
      提取了 2 条说法：
      :::claim 部分支持
      消费从 2023 年下半年开始降温 [[s1]]
      官方数据只能核对时间点 [[s7]]
      :::
      :::claim 无法核实
      商圈排队明显变短 [[s2]]
      :::
      外部资料只读取了 1 篇网页。
      """.trimIndent()
    )
    assertEquals(4, blocks.size)
    val first = blocks[1] as AnswerBlock.Claim
    assertEquals("部分支持", first.value.status)
    assertEquals(1, first.value.index)
    assertEquals("消费从 2023 年下半年开始降温 [[s1]]", first.value.claim)
    assertEquals("官方数据只能核对时间点 [[s7]]", first.value.note)
    val second = blocks[2] as AnswerBlock.Claim
    assertEquals(2, second.value.index)
    assertEquals("", second.value.note)
    assertEquals("外部资料只读取了 1 篇网页。", (blocks[3] as AnswerBlock.Markdown).text)
  }

  @Test
  fun `围栏内与未知状态按字面保留`() {
    val fenced = splitAnswerBlocks("```\n:::claim 支持\n示例\n:::\n```")
    assertTrue(fenced.all { it is AnswerBlock.Markdown })
    assertTrue(splitAnswerBlocks(":::claim 大概支持\n说法\n:::").all { it is AnswerBlock.Markdown })
  }
}
