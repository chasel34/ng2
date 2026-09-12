package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `api/search` domain 全量对拍(8 条,票 07)。
 *
 * `parseBoardSearch` 的条目**直接以数字键挂在 data 上**(不像版块收藏包一层 `data["0"]`);
 * `parseUserSearchInput` 锁的是「整段都是数字才按 uid 查」——NGA 用户名可以带数字。
 */
class SearchGoldenTest {

  @Test
  fun `api-search 金样本全量对拍`() = runGoldenDomain("api/search") {
    fn("parseBoardSearch") { case -> golden(parseBoardSearch(case.parserInput())) }
    fn("parseUserSearchInput") { case ->
      golden<UserSearchQuery?>(parseUserSearchInput(case.inputString()))
    }
  }
}
