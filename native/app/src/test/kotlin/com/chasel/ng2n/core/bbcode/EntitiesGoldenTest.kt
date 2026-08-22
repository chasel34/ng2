package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `entities` domain 全量对拍(20 条)。
 *
 * BBCode 的前置件:读的时候跑两轮实体解码(NGA 的双重转义),写的时候反着来一次。
 * 票 09 的 BBCode 解析器会大量用到 [unescapeNgaText],所以先在这里锁死。
 */
class EntitiesGoldenTest {

  @Test
  fun `entities 金样本全量对拍`() = runGoldenDomain("entities") {
    fn("unescapeNgaText") { case -> unescapeNgaText(case.inputString()) }
    fn("escapeForSubmit") { case -> escapeForSubmit(case.inputString()) }
  }
}
