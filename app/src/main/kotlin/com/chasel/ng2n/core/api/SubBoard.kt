package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.QueryValue
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 子版块订阅 / 屏蔽(CONTEXT.md「子版块」,API 文档 §1.4)。直译 `src/core/api/sub-board.ts`。
 *
 * ```
 * POST nuke.php?__lib=user_option&__act=set&raw=3&{del|add}=<filterId>
 * form: fid=<父版块 fid>, type=<0|1>, info=add_to_block_tids
 * ```
 *
 * 这个接口有两处反直觉,都是从两份客户端源码里试出来的,没有文档:
 *
 * 1. **参数名即操作,而且语义是反的**:`del=<id>` 是订阅(把它从屏蔽表里删掉),
 *    `add=<id>` 是屏蔽。请求体里的 `info=add_to_block_tids` 说明了这一点——
 *    服务端维护的是一张**屏蔽** tid 表,订阅只是「不在表里」。
 * 2. **`type` 会把 1 再反转一次**:Android 源码里 `type == 1` 时订阅用 `del`,
 *    其它 type 反过来(原注释:「NGA 后台好变态啊,某个板块的操作居然是反的」)。
 *    type 由子版块有没有 `filter_id` 决定(`TopicList.kt` 的 `parseSubBoard`)。
 */

/** `attributes` 命中这几个值视为已订阅(API 文档 §13 第 13 条)。 */
val SUBSCRIBED_ATTRIBUTES: List<Long> = listOf(7, 558, 542, 2606, 2590, 4654)

/** `attributes` 大于这个值才谈得上订阅/屏蔽,小的那些服务端不让改。 */
const val FILTERABLE_ATTRIBUTES_MIN = 40L

/**
 * 用户视角的三态——**修「白名单误报」**(spec §一.5;
 * `.scratch/ui-polish-2026-08-20/issues/02-subboard-blocked-state-false.md`)。
 *
 * RN 版只有两态:`attributes` 不在白名单里一律落到 `subscribed: false`,UI 直接画成
 * **已屏蔽**。实测「网络游戏综合」从没被屏蔽过却显示已屏蔽,原因就是白名单漏了取值
 * (那六个魔法数出自 MNGA 源码,原注释是 "how can I fucking know this ??")。
 *
 * 这里加出 [UNKNOWN] 一档:白名单是「已订阅」的**唯一证据**,没命中只说明我们认不出来,
 * 不等于被屏蔽。UI 该画成「未知」而不是替服务端下结论。
 * **判据本身一个字没改**——魔法数表随时可能随 NGA 更新失效,改它需要带登录态重新抓包
 * (issue 里的下一步 1/2,待所有者)。
 */
enum class SubBoardSubscription { SUBSCRIBED, BLOCKED, UNKNOWN }

@Serializable
data class SubBoardState(
  /** 当前是否已订阅。**未订阅 ≠ 已屏蔽**,后者要 [known] 也为真才成立 */
  val subscribed: Boolean,
  /** 能不能改。false 时 UI 只展示,不给开关 */
  val filterable: Boolean,
  /**
   * 这个状态是不是「有把握的」。
   *
   * 只有两个来源算数:`attributes` 命中白名单(⇒ 已订阅),或者本地刚做完一次操作
   * ([nextSubBoardState])。其余一律 [SubBoardSubscription.UNKNOWN]。
   *
   * `@Transient`:金样本锁的是 RN 版那两个字段的取值(`api/sub-board` 16 条),
   * 这一位是修缺陷新加的**本地**信息,不进序列化形态。
   */
  @Transient val known: Boolean = false,
) {
  /** 三态视图,UI 用它决定画「已订阅 / 已屏蔽 / 未知」。 */
  val subscription: SubBoardSubscription
    get() = when {
      subscribed -> SubBoardSubscription.SUBSCRIBED
      known -> SubBoardSubscription.BLOCKED
      else -> SubBoardSubscription.UNKNOWN
    }
}

/**
 * 按魔法数判定一个子版块的订阅状态。
 *
 * 命中白名单 = 已订阅(且这一判定是有把握的);没命中 = 我们不知道(见 [SubBoardSubscription])。
 */
fun subBoardState(attributes: Long): SubBoardState {
  val subscribed = attributes in SUBSCRIBED_ATTRIBUTES
  return SubBoardState(
    subscribed = subscribed,
    filterable = attributes > FILTERABLE_ATTRIBUTES_MIN,
    known = subscribed,
  )
}

/** 用户视角的两个动作。 */
enum class SubBoardAction { SUBSCRIBE, BLOCK }

/**
 * 这次操作该用哪个参数名。基准规则是「订阅 = del」,`type` 为 0 时整个反过来。
 */
fun subBoardOptionParam(action: SubBoardAction, filterType: Int): String {
  val subscribeParam = if (filterType == 1) "del" else "add"
  return if (action == SubBoardAction.SUBSCRIBE) {
    subscribeParam
  } else {
    if (subscribeParam == "del") "add" else "del"
  }
}

/**
 * 操作后本地该显示的状态(服务端不回新的 attributes,只回一句「操作成功」)。
 * 动作是我们自己发的,所以结果是**有把握的**——[SubBoardState.known] 置真。
 */
fun nextSubBoardState(state: SubBoardState, action: SubBoardAction): SubBoardState =
  state.copy(subscribed = action == SubBoardAction.SUBSCRIBE, known = true)

/**
 * 订阅或屏蔽一个子版块。失败由 envelope 抛 `kind = SERVER`,能返回就是成功。
 *
 * @param parentFid 父版块的 fid(子版块列表是从哪个版块的 `__F` 里来的)
 */
suspend fun setSubBoardOption(
  client: NgaClient,
  subBoard: SubBoard,
  parentFid: Long,
  action: SubBoardAction,
) {
  val param = subBoardOptionParam(action, subBoard.filterType)

  client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
      operation = Operation.WRITE,
      query = linkedMapOf<String, QueryValue?>(
        "__lib" to QueryValue.Text("user_option"),
        "__act" to QueryValue.Text("set"),
        "raw" to QueryValue.Num(3),
        // 参数名本身就是操作,所以只放这一个,另一个连键都不能出现
        param to QueryValue.Num(subBoard.filterId),
      ),
      form = queryOf(
        "fid" to parentFid,
        "type" to subBoard.filterType,
        "info" to "add_to_block_tids",
      ),
    ),
  )
}
