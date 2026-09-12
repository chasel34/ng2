package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class SanitizeGoldenTest {

  @Test
  fun `sanitize 金样本全量对拍`() = runGoldenDomain("sanitize") {
    fn("sanitizeNgaJson") { case -> sanitizeNgaJson(case.inputString()) }
  }
}
