package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.SubBoard
import com.chasel.ng2n.core.api.SubBoardAction
import com.chasel.ng2n.core.api.SubBoardState
import com.chasel.ng2n.core.api.nextSubBoardState
import com.chasel.ng2n.core.api.setSubBoardOption
import com.chasel.ng2n.core.api.subBoardState
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.session.SubBoardOverrides
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子版块订阅 / 屏蔽(CONTEXT.md「子版块」)—— 直译 RN 侧 `store/sub-boards.ts`。
 *
 * 服务端**不回新的 attributes**,只回一句「操作成功」;而 attributes 是随主题列表
 * (`thread.php` 的 `__F.sub_forums`)一起下来的,重拉一次列表只为看一个开关太贵
 * (ADR-0002)。所以改过的状态记在**会话级**内存里(票 14 的 [SubBoardOverrides],
 * 那份 KDoc 写清了为什么不能落盘),盖在解析出来的 attributes 上:
 * 显示 = 本地改动 ?? 魔法数判定。下次重进版块拉到新列表,自然回到服务端口径。
 */
@Singleton
class SubBoardRepository @Inject constructor(
  private val client: NgaClient,
  private val overrides: SubBoardOverrides,
) {

  fun keyOf(uid: String, subBoard: SubBoard): String =
    overrides.keyOf(uid, subBoard.filterId.toString())

  /**
   * 一个子版块此刻该显示的状态。游客态没有本地改动,一律按 attributes 显示。
   *
   * 三态(SUBSCRIBED / BLOCKED / UNKNOWN)是票 07 修掉的「白名单误报」:
   * 白名单是「已订阅」的唯一证据,没命中只说明我们认不出来,不等于被屏蔽。
   */
  fun stateOf(uid: String?, subBoard: SubBoard, overrideMap: Map<String, Boolean>): SubBoardState {
    val parsed = subBoardState(subBoard.attributes)
    if (uid == null) return parsed
    val override = overrideMap[keyOf(uid, subBoard)] ?: return parsed
    return nextSubBoardState(parsed, if (override) SubBoardAction.SUBSCRIBE else SubBoardAction.BLOCK)
  }

  /**
   * 切订阅 / 屏蔽。乐观切换:开关点了就该立刻动,失败回滚并把服务端的话交给调用方去说。
   *
   * @param parentFid 父版块 fid(子版块列表是从哪个版块来的)
   */
  suspend fun toggle(uid: String, subBoard: SubBoard, parentFid: Long, action: SubBoardAction) {
    val key = keyOf(uid, subBoard)
    // 在途判定在这里做,不看 beginToggle 的返回值:它的 `null` 有两个意思
    // (「已经在途」与「本来就没有覆盖」),分不开(票外问题,已记进票 16 Comments)
    if (key in overrides.inFlight.value) return
    val previous = overrides.beginToggle(key, action == SubBoardAction.SUBSCRIBE)
    try {
      setSubBoardOption(client, subBoard = subBoard, parentFid = parentFid, action = action)
    } catch (error: Throwable) {
      overrides.rollback(key, previous)
      throw error
    } finally {
      overrides.endToggle(key)
    }
  }

  val overrideFlow get() = overrides.overrides
  val inFlightFlow get() = overrides.inFlight
}
