package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `sanitize` domain 全量对拍(22 条,票 04)。
 *
 * 纯字符串变换,**不做 JSON 解析**:`expected` 就是洗完的文本,逐 UTF-16 码元比。
 * `capture-*` 那 6 条的 `input` 是真机抓包字节按 `decodeResponseBody` 解出来的文本。
 */
class SanitizeGoldenTest {

  @Test
  fun `sanitize 金样本全量对拍`() = runGoldenDomain("sanitize") {
    fn("sanitizeNgaJson") { case -> sanitizeNgaJson(case.inputString()) }
  }
}
