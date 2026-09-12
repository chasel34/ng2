package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * `errors` domain 全量对拍(32 条,票 04):`core/net/Errors.kt` + `ServerText.kt`。
 *
 * - `isFakeError` ×10 / `isAuthLevelServerError` ×3:入参是服务端原话(字符串);
 * - `extractServerError` ×11:入参是**响应顶层对象**,不是 error 本身;
 * - `stripServerHtml` ×8:入参是带 HTML 的说明文字。
 */
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
