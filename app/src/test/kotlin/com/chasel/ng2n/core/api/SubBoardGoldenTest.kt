package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SubBoardGoldenTest {

  @Test
  fun `api-sub-board 金样本全量对拍`() = runGoldenDomain("api/sub-board") {
    fn("subBoardState") { case -> golden(subBoardState(case.longField("attributes"))) }
    fn("subBoardOptionParam") { case ->
      subBoardOptionParam(case.action(), case.longField("filterType").toInt())
    }
    fn("nextSubBoardState") { case ->
      val state: SubBoardState = ApiGoldenJson.decodeFromJsonElement(case.field("state"))
      golden(nextSubBoardState(state, case.action()))
    }
  }

  @Test
  fun `白名单命中才是已订阅,且这一判定是有把握的`() {
    for (attributes in SUBSCRIBED_ATTRIBUTES) {
      assertEquals(SubBoardSubscription.SUBSCRIBED, subBoardState(attributes).subscription)
    }
  }

  @Test
  fun `白名单外一律未知——RN 版这里画的是「已屏蔽」,那是误报`() {
    for (attributes in listOf(0L, 40L, 41L, 4655L)) {
      assertEquals(SubBoardSubscription.UNKNOWN, subBoardState(attributes).subscription)
      assertEquals(false, subBoardState(attributes).subscribed)
    }
  }

  @Test
  fun `本地刚操作过的状态是有把握的——屏蔽就是屏蔽,不是未知`() {
    val unknown = subBoardState(4655)
    assertEquals(
      SubBoardSubscription.BLOCKED,
      nextSubBoardState(unknown, SubBoardAction.BLOCK).subscription,
    )
    assertEquals(
      SubBoardSubscription.SUBSCRIBED,
      nextSubBoardState(unknown, SubBoardAction.SUBSCRIBE).subscription,
    )
  }
}

private fun com.chasel.ng2n.golden.GoldenCase.action(): SubBoardAction =
  when (val raw = field("action").jsonPrimitive.content) {
    "subscribe" -> SubBoardAction.SUBSCRIBE
    "block" -> SubBoardAction.BLOCK
    else -> error("$resourcePath: 认不出的 action `$raw`")
  }
