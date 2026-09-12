package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

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
