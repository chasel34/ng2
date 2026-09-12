package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class ErrorsGoldenTest {

  @Test
  fun `errors 金样本全量对拍`() = runGoldenDomain("errors") {
    fn("isFakeError") { case -> isFakeError(case.inputString()) }
    fn("isAuthLevelServerError") { case -> isAuthLevelServerError(case.inputString()) }
    fn("stripServerHtml") { case -> stripServerHtml(case.inputString()) }
    fn("extractServerError") { case ->
      extractServerError(case.input)?.let { error ->
        buildJsonObject {
          put("code", error.code)
          put("message", error.message)
        }
      }
    }
  }
}
