package com.chasel.ng2n.data.diagnostics

import com.chasel.ng2n.data.account.FakePreferencesDataStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 实验室页那两个「发出去」的入口(票 17c)拿到的文本必须已经脱敏(P1-04)。
 *
 * `DiagnosticLogTest` 盯的是 [formatDiagnostic] 这个纯函数;这里盯的是**屏幕真正读的
 * 那两条路** —— 落盘日志(`exportText`)与本次运行的请求表(`runLog`)。
 * 两者都由 [DiagnosticLogStore] 在**写入时**收口,UI 侧不做二次拼装。
 */
class DiagnosticExportTest {

  private fun store() = DiagnosticLogStore(FakePreferencesDataStore())

  private fun failing(params: Map<String, String>) = DiagnosticRecord(
    at = 1_700_000_000_000,
    path = "read.php",
    params = params,
    message = "整条链都没拿到数据",
    attempts = listOf(
      AttemptLog(
        strategy = "direct",
        format = "json",
        host = "https://bbs.nga.cn",
        userAgent = "windowsPhone",
        uid = "42",
        error = AttemptError(kind = "http", message = "403", status = 403),
      ),
    ),
  )

  @Test
  fun `导出诊断日志里没有 fav 码 只留键名`() = runTest {
    val store = store()
    store.record(failing(mapOf("tid" to "42800000", "fav" to "deadbeef")))

    val text = store.exportText()

    assertTrue(text.contains("tid=42800000"), text)
    assertTrue(text.contains("fav=$REDACTED_PLACEHOLDER"), text)
    assertFalse(text.contains("deadbeef"), text)
  }

  @Test
  fun `本次运行的请求表同样只留键名`() = runTest {
    val store = store()
    store.record(failing(mapOf("fid" to "650", "key" to "某个搜索词")))

    val entry = store.runLog.first()

    assertEquals("650", entry.params["fid"])
    assertEquals(REDACTED_PLACEHOLDER, entry.params["key"])
    assertFalse(entry.params.values.any { it.contains("某个搜索词") })
  }
}
