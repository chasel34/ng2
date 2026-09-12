package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

/**
 * `api/topic-list` domain 全量对拍(22 条,票 07)。
 *
 * 锁住的几处:`quote_from` 才是真实 tid、`parent` 的对象/字符串化 JSON 双形态、
 * 负数 fid 的符号还原(两条来源都要)、`__ROWS` 是空串时退回 `__T__ROWS`、
 * 以及 `listStructure` 分清「没帖子」与「没拿到」。
 */
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
