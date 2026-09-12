package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * `api/user-profile` domain 全量对拍(5 条,票 07)。
 *
 * `args.nowSeconds` 是判「禁言是否还在有效期内」的基准时刻,金样本固定成
 * 1786100000——不固定它期望值就不可复现。
 * `missing` / `avatar-only` 两条期望是 `null`:解不出用户时**返回 null 而不是抛**,
 * 「找不到用户 / 响应是空的」怎么措辞留给调用方。
 */
class UserProfileGoldenTest {

  @Test
  fun `api-user-profile 金样本全量对拍`() = runGoldenDomain("api/user-profile") {
    fn("parseUserProfile") { case ->
      val now = case.arg("nowSeconds")?.jsonPrimitive?.content?.toLong()
        ?: error("${case.resourcePath}: parseUserProfile 必须给 args.nowSeconds")
      golden(parseUserProfile(case.parserInput(), now))
    }
  }
}
