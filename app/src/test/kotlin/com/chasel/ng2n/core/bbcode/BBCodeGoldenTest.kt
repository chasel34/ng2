package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class BBCodeGoldenTest {

  @Test
  fun `bbcode 金样本全量对拍`() = runGoldenDomain("bbcode") {
    fn("parseBBCode") { case -> encodeBBCode(parseBBCode(case.inputString())) }
  }
}
