package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class TopicFavorGoldenTest {

  @Test
  fun `api-topic-favor 金样本全量对拍`() = runGoldenDomain("api/topic-favor") {
    fn("parseFavoriteFolders") { case -> golden(parseFavoriteFolders(case.parserInput())) }
  }
}
