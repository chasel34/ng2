package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class TopicDetailGoldenTest {

  @Test
  fun `api-topic-detail 金样本全量对拍`() = runGoldenDomain("api/topic-detail") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseTopicDetail") { case ->
      val context = case.arg("context")?.jsonPrimitive?.content
        ?: error("${case.resourcePath}: parseTopicDetail 必须给 args.context")
      golden(parseTopicDetail(case.parserInput(), context))
    }
    fn("parseAvatarUrl") { case -> parseAvatarUrl(case.field("raw")) }
  }
}
