package com.chasel.ng2n.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogTest {

  private val attempt = AttemptLog(
    strategy = "direct",
    format = "json",
    host = "https://bbs.nga.cn",
    userAgent = "windowsPhone",
    uid = "42",
    error = AttemptError(kind = "http", message = "Forbidden", status = 403),
  )

  @Test
  fun `隐藏主题的 fav 码不进日志`() {
    val text = formatDiagnostic(
      DiagnosticRecord(
        at = 1_786_100_000_000,
        path = "read.php",
        params = mapOf("tid" to "44191387", "page" to "3", "fav" to "s3cr3t-fav-code"),
        message = "全链失败",
        attempts = listOf(attempt),
      ),
    )
    assertFalse(text.contains("s3cr3t-fav-code"), text)
    assertTrue(text.contains("fav=$REDACTED_PLACEHOLDER"), text)
    assertTrue(text.contains("tid=44191387"), text)
    assertTrue(text.contains("page=3"), text)
  }

  @Test
  fun `搜索词不进日志`() {
    val text = formatDiagnostic(
      DiagnosticRecord(
        at = 1_786_100_000_000,
        path = "thread.php",
        params = mapOf("key" to "某个很私人的搜索词", "fid" to "-7", "content" to "1", "page" to "1"),
        message = "全链失败",
        attempts = listOf(attempt),
      ),
    )
    assertFalse(text.contains("某个很私人的搜索词"), text)
    assertTrue(text.contains("key=$REDACTED_PLACEHOLDER"), text)
    assertTrue(text.contains("fid=-7"), text)
  }

  @Test
  fun `整张屏蔽词表不进日志`() {
    val table = "u:12345,某用户\\nw:讨厌的关键词\\nw:另一个"
    val text = formatDiagnostic(
      DiagnosticRecord(
        at = 1_786_100_000_000,
        path = "nuke.php",
        params = mapOf("data" to table),
        message = "全链失败",
        attempts = listOf(attempt),
      ),
    )
    assertFalse(text.contains("讨厌的关键词"), text)
    assertFalse(text.contains("某用户"), text)
    assertTrue(text.contains("data=$REDACTED_PLACEHOLDER"), text)
  }

  @Test
  fun `白名单之外一律默认拒绝 新参数不必逐个声明`() {
    val redacted = redactDiagnosticParams(
      mapOf("tid" to "1", "some_new_param" to "谁知道里面是什么", "__output" to "8"),
    )
    assertEquals("1", redacted["tid"])
    assertEquals(REDACTED_PLACEHOLDER, redacted["some_new_param"])
    assertFalse("__output" in redacted)
  }

  @Test
  fun `错误页那一行摘要同样脱敏`() {
    val summary = diagnosticSummary(
      DiagnosticRecord(
        at = 1,
        path = "read.php",
        params = mapOf("tid" to "1", "fav" to "s3cr3t"),
        message = "x",
        attempts = listOf(attempt),
      ),
    )
    assertFalse(summary.contains("s3cr3t"))
    assertTrue(summary.contains("ua=windowsPhone"))
  }

  @Test
  fun `凭证从来不在日志里 只有 uid`() {
    val text = formatDiagnostic(
      DiagnosticRecord(
        at = 1,
        path = "read.php",
        params = mapOf("tid" to "1"),
        message = "x",
        attempts = listOf(attempt),
      ),
    )
    assertTrue(text.contains("uid=42"), text)
    assertFalse(text.contains("ngaPassportCid"), text)
  }

  @Test
  fun `日志裁到 50 条 最新的在最后`() {
    var log = emptyList<String>()
    repeat(60) { index ->
      log = appendDiagnosticLog(
        log,
        DiagnosticRecord(at = index.toLong(), path = "p$index", message = "m$index"),
      )
    }
    assertEquals(DIAGNOSTIC_LOG_LIMIT, log.size)
    assertTrue(log.last().contains("p59"), log.last())
    assertTrue(log.first().contains("p10"), log.first())
  }

  @Test
  fun `成功记录带落点摘要 失败记录带每一档为什么败`() {
    val ok = formatDiagnostic(
      DiagnosticRecord(
        at = 0,
        path = "app_api.php",
        params = mapOf("fid" to "-7"),
        message = "ok",
        attempts = listOf(attempt.copy(error = null)),
        success = OutcomeSummary(
          strategy = "direct",
          format = "json",
          host = "https://bbs.nga.cn",
          keys = listOf("__T", "__ROWS"),
          rows = 35,
        ),
      ),
    )
    assertTrue(ok.contains("成功:"), ok)
    assertTrue(ok.contains("data{__T,__ROWS} 35 条"), ok)

    val failed = formatDiagnostic(
      DiagnosticRecord(at = 0, path = "read.php", message = "全垮了", attempts = listOf(attempt)),
    )
    assertTrue(failed.contains("失败:全垮了"), failed)
    assertTrue(failed.contains("http 403: Forbidden"), failed)
  }
}
