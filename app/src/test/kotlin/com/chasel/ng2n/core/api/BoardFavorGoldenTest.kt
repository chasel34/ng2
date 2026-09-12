package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class BoardFavorGoldenTest {

  @Test
  fun `api-board-favor 金样本全量对拍`() = runGoldenDomain("api/board-favor") {
    fn("parseBoardFavorites") { case -> golden(parseBoardFavorites(case.parserInput())) }
    fn("parseBoardIdInput") { case -> parseBoardIdInput(case.inputString()) }
  }
}
