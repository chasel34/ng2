package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class EntitiesGoldenTest {

  @Test
  fun `entities 金样本全量对拍`() = runGoldenDomain("entities") {
    fn("unescapeNgaText") { case -> unescapeNgaText(case.inputString()) }
    fn("escapeForSubmit") { case -> escapeForSubmit(case.inputString()) }
  }
}
