package com.chasel.ng2n.ui.common

import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 24:空态/提示条上那一句失败文案的映射([failureText])。
 *
 * 现场原话是版块列表印出了
 * `Unable to resolve host "bbs.ngacn.cc": No address associated with hostname` ——
 * 英文异常原文 + 反封锁链当时轮换到的域名。两件事都不该上屏,而同一时刻主题详情的
 * 失败面板印的是中文的「连不上服务器」:**两条路必须是同一张文案表**
 * (`describeFetchFailure`)。
 */
class FailureTextTest {

  /** 现场那一发:okhttp 的 `UnknownHostException` 被链包成 NETWORK 档。 */
  private val unresolvedHost = NgaError(
    NgaErrorKind.NETWORK,
    """Unable to resolve host "bbs.ngacn.cc": No address associated with hostname""",
    via = "format-rotation",
    cause = UnknownHostException("""Unable to resolve host "bbs.ngacn.cc""""),
  )

  @Test
  fun `连不上服务器 —— 不印异常原文 也不漏轮换到的域名`() {
    val text = failureText(unresolvedHost)
    assertEquals("连不上服务器", text)
    assertFalse("bbs.ngacn.cc" in text, "域名是反封锁链的内部状态,不该给用户看")
    assertFalse("Unable to resolve host" in text)
  }

  @Test
  fun `版块与主题详情走同一张表`() {
    // 主题详情的失败面板用的就是 describeFetchFailure 的 headline
    assertEquals(
      com.chasel.ng2n.core.net.describeFetchFailure(unresolvedHost).headline,
      failureText(unresolvedHost),
    )
  }

  @Test
  fun `HTTP 档带上状态码 但只带状态码`() {
    val error = NgaError(NgaErrorKind.HTTP, "Forbidden", status = 403)
    assertEquals("服务端返回 HTTP 403", failureText(error))
  }

  @Test
  fun `解析失败是被封的常见形态 也说人话`() {
    assertEquals("响应内容解析不了", failureText(NgaError(NgaErrorKind.PARSE, "unexpected token < in JSON")))
  }

  @Test
  fun `服务端自己把话说清楚的那一档 照搬原文`() {
    // 论坛给的是中文说明,比我们编的强(与主题详情面板同一条口径)
    assertEquals("你没有权限查看本版面", failureText(NgaError(NgaErrorKind.SERVER, "你没有权限查看本版面")))
  }

  @Test
  fun `认不出的异常退化成兜底话术 而不是把 message 印上去`() {
    assertEquals(FAILURE_FALLBACK, failureText(null))
    assertEquals(FAILURE_FALLBACK, failureText(UnknownHostException("""Unable to resolve host "bbs.ngacn.cc"""")))
    assertEquals(FAILURE_FALLBACK, failureText(IllegalStateException("kotlin.KotlinNullPointerException")))
  }

  @Test
  fun `每一档都是中文 都不含 ASCII 句子`() {
    val texts = listOf(
      failureText(unresolvedHost),
      failureText(NgaError(NgaErrorKind.HTTP, "Forbidden", status = 403)),
      failureText(NgaError(NgaErrorKind.PARSE, "boom")),
      failureText(NgaError(NgaErrorKind.UNAVAILABLE, "no strategy left")),
      failureText(null),
    )
    for (text in texts) {
      assertTrue(text.any { it.code > 0x2E80 }, "「$text」应当是给人看的中文")
      assertFalse("Exception" in text || "java." in text, "「$text」漏了异常原文")
    }
  }
}
