package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class SearchGoldenTest {

  @Test
  fun `api-search 金样本全量对拍`() = runGoldenDomain("api/search") {
    fn("parseBoardSearch") { case -> golden(parseBoardSearch(case.parserInput())) }
    fn("parseUserSearchInput") { case ->
      golden<UserSearchQuery?>(parseUserSearchInput(case.inputString()))
    }
  }
}
