package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `anonymous` domain 全量对拍(30 条)。
 *
 * 期望值来自 NGA 官方前端 `commonui.anonyName` 跑出来的输出,不是本实现自证。
 * 两处怪癖锁在这里:hex[5] 官方就是跳过的、百家姓表只有 255 字所以 `0xff` 掉字符。
 */
class AnonymousGoldenTest {

  @Test
  fun `anonymous 金样本全量对拍`() = runGoldenDomain("anonymous") {
    fn("decodeAnonymousName") { case ->
      // 对拍框架的 toGoldenJson 认 Map/Iterable,直接摆成期望的形状最省事
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

  // --- 手工移植:`anonymous.test.ts` 里金样本没覆盖到的断言 ----------------------

  @Test
  fun `真实 hex 的解码结果与设计稿从真机转录的假名对得上`() {
    // 这串 hex 来自 thread_php?fid=-7 的真实响应(主题「技师请我吃饭(续)」);
    // 设计稿主题列表 mock 里恰好有个作者叫「卯邱潘」,是设计师照真机转录的,
    // 而且它在本实现存在之前就随初始提交进了仓库——两条独立来源对上了。
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
