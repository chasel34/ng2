package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

class TopicListGoldenTest {

  @Test
  fun `api-topic-list 金样本全量对拍`() = runGoldenDomain("api/topic-list") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseTopicList") { case -> golden(parseTopicList(case.parserInput())) }
    fn("hasTopicListStructure") { case -> hasTopicListStructure(case.valueField()) }
    fn("rejectNonTopicList") { case -> rejectNonTopicList(case.envelopeInput()) }
    fn("serverEmptyTopicList") { golden(serverEmptyTopicList()) }
    fn("mergeTopicPages") { case ->
      val pages: List<TopicList> = ApiGoldenJson.decodeFromJsonElement(case.valueField())
      golden(mergeTopicPages(pages))
    }
  }
}
