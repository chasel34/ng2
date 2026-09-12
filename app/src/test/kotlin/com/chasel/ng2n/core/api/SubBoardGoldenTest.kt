package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `api/sub-board` domain 全量对拍(16 条,票 07)。
 *
 * 金样本锁的是 RN 版那两个字段(`subscribed` / `filterable`)的取值,
 * 修「白名单误报」新加的第三态([SubBoardState.subscription])是 `@Transient` 的本地信息,
 * 不进序列化形态,所以对拍照旧全绿——**判据本身一个字没改**。
 * 新那一档由下面的手写用例钉住。
 */
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

  // --- 修「白名单误报」:白名单外不再一口咬定「已屏蔽」 -------------------------

  @Test
  fun `白名单命中才是已订阅,且这一判定是有把握的`() {
    for (attributes in SUBSCRIBED_ATTRIBUTES) {
      assertEquals(SubBoardSubscription.SUBSCRIBED, subBoardState(attributes).subscription)
    }
  }

  @Test
  fun `白名单外一律未知——RN 版这里画的是「已屏蔽」,那是误报`() {
    // 4655 = 4654(已订阅)+1,40/41 是实测里可改与不可改的边界
    for (attributes in listOf(0L, 40L, 41L, 4655L)) {
      assertEquals(SubBoardSubscription.UNKNOWN, subBoardState(attributes).subscription)
      // RN 版的两个字段取值不变,金样本照旧
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

/** `input.action` 是 `subscribe` / `block`。 */
private fun com.chasel.ng2n.golden.GoldenCase.action(): SubBoardAction =
  when (val raw = field("action").jsonPrimitive.content) {
    "subscribe" -> SubBoardAction.SUBSCRIBE
    "block" -> SubBoardAction.BLOCK
    else -> error("$resourcePath: 认不出的 action `$raw`")
  }
