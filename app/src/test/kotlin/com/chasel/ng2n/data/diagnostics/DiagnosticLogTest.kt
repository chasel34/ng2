package com.chasel.ng2n.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **票 14 验收项③:诊断日志导出样本无敏感字段**(修 P1-04)。
 *
 * 审计 2026-08-20 列出的三条实锤泄露源,逐条断言它们不会出现在导出文本里:
 * - `read.php` 的 `fav`(隐藏主题访问码,`src/core/api/topic-detail.ts:374-381`);
 * - `thread.php` 的 `key`(搜索词,`src/core/api/search.ts:68-83`);
 * - `nuke.php&__act=set_block_word` 的 `data`(整张屏蔽词表,`block-word.ts:165-175`)。
 *
 * 同时钉住反面:排障真正要看的结构性字段(tid/page/fid)必须还在,
 * 否则脱敏就把日志本身废掉了。
 */
class DiagnosticLogTest {

  private val attempt = AttemptLog(
    strategy = "direct",
    format = "json",
    host = "https://bbs.nga.cn",
    userAgent = "windowsPhone",
    uid = "42",
    error = AttemptError(kind = "http", message = "Forbidden", status = 403),
  )

  // ------------------------------------------------------------ 脱敏

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
    // 排障要用的结构性字段还在
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
    // `__` 开头的框架参数上游就剔掉了,这里再挡一次
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
    // AttemptLog 压根没有放 cid 的地方 —— 这条断言钉住的是「以后也别加」
    assertTrue(text.contains("uid=42"), text)
    assertFalse(text.contains("ngaPassportCid"), text)
  }

  // ------------------------------------------------------------ 50 条上限

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
