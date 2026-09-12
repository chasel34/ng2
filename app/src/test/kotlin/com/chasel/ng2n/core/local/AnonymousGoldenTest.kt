package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnonymousGoldenTest {

  @Test
  fun `anonymous 金样本全量对拍`() = runGoldenDomain("anonymous") {
    fn("decodeAnonymousName") { case ->
      decodeAnonymousName(case.inputString())?.let { decoded ->
        mapOf(
          "name" to decoded.name,
          "colors" to listOf(decoded.colors.first, decoded.colors.second),
        )
      }
    }
    fn("resolveAuthorName") { case -> resolveAuthorName(case.inputString()) }
    fn("isAnonymousAuthor") { case -> isAnonymousAuthor(case.inputString()) }
  }

  @Test
  fun `真实 hex 的解码结果与设计稿从真机转录的假名对得上`() {
    val decoded = decodeAnonymousName("#anony_d43225f5a338ca2efea68a14773537e6")
    assertEquals("卯邱潘巳邵卢", decoded?.name)
    assertEquals("卯邱潘", decoded?.name?.take(3))
  }

  @Test
  fun `hex 只认小写与 32 位,别的形状都还不出来`() {
    assertNull(decodeAnonymousName("#anony_0123456789ABCDEF0123456789ABCDEF"))
    assertNull(decodeAnonymousName("#anony_0123"))
    assertNull(decodeAnonymousName("#ANONYMOUS#"))
    assertNull(decodeAnonymousName("春曰影"))
  }
}
