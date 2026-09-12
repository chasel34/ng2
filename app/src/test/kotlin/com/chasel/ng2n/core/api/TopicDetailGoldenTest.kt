package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * `api/topic-detail` domain 全量对拍(11 条,票 07)。
 *
 * `parseTopicDetail` 的 `args.context` 是**请求级 context**(给匿名用户 id 加前缀,
 * 同一次请求内必须一致、不同请求之间必须不同);金样本里固定成 `golden`,
 * 否则匿名楼层的 `authorKey` 每跑一次都不一样,期望值无从写起。
 */
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
