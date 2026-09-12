package com.chasel.ng2n.core.net.encoding

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class DecodeBodyGoldenTest {

  @Test
  fun `decode-body 金样本全量对拍`() = runGoldenDomain("decode-body") {
    fn("parseCharset") { case -> parseCharset(case.stringFieldOrNull("contentType")) }
    fn("decodeResponseBody") { case ->
      decodeResponseBody(case.bytesField(), case.stringFieldOrNull("contentType"))
    }
  }
}
