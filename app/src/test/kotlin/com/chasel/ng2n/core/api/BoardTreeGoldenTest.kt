package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

class BoardTreeGoldenTest {

  @Test
  fun `api-board-tree 金样本全量对拍`() = runGoldenDomain("api/board-tree") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseBoardTree") { case -> golden(parseBoardTree(case.parserInput())) }
    fn("pickActiveAnnouncement") { case ->
      val announcements: List<HomeAnnouncement> =
        ApiGoldenJson.decodeFromJsonElement(case.field("announcements"))
      golden(pickActiveAnnouncement(announcements, case.longField("now")))
    }
  }
}
