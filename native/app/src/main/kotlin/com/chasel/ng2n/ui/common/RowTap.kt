package com.chasel.ng2n.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 列表行的点击 —— `Modifier.clickable` 的替身,**横划一把不会算成点这一行**(票 23)。
 *
 * ## 为什么 `clickable` 自己不行
 *
 * `clickable` 的 `waitForUpOrCancellation` 只在两种情况下取消:手势被别人 `consume`,
 * 或手指**离开这一行的边界**。横向平移 700px 两条都不满足 —— 落点那一行从头到尾
 * 都在手指底下,于是抬手时照样报一次点击,当场跳进那个主题。
 * 列表屏又没有任何横向手势节点来消费这一发(主题详情不中招,是因为那里的
 * `HorizontalPager` 把横向拖动吃掉了)。
 *
 * ## 判据与消费通道
 *
 * 和抽屉那处 2026-08-22 修好的坑同一套做法(`ui/drawer/DrawerHost.kt`):
 * 手势跑在 [PointerEventPass.Initial] 上,**认领之前一个事件都不消费**,
 * 所以纵向滚动、长按、子元素(行内的删除钮)照常走;一旦横向位移过了 touch slop
 * 且横向压过纵向([shouldCancelRowTap]),就把这一发以及之后每一发都 `consume()` ——
 * 内层 `clickable` 收到已消费的事件即取消这次按压,抬手不再报点击。
 *
 * Initial 是父 → 子,所以消费一定早于 `clickable` 处理这一发;挂在 Main(子 → 父)
 * 上时序反过来,抽屉那次实测就是这么漏过去的。
 *
 * 只用在**整行**这一档:顶栏按钮、对话框按钮那种小目标上横划本来也划不出 slop,
 * 没必要多挂一个 pointerInput。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.rowClickable(
  onClickLabel: String? = null,
  enabled: Boolean = true,
  onLongClick: (() -> Unit)? = null,
  onLongClickLabel: String? = null,
  onClick: () -> Unit,
): Modifier = this
  .cancelTapOnHorizontalDrag(enabled)
  .then(
    // 没长按动作的行照旧走 `clickable`:`combinedClickable` 会为了等长按而把点击
    // 判定往后拖(要先确认这不是一次长按),列表上每一行都吃这一下不值当
    if (onLongClick == null) {
      Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
    } else {
      Modifier.combinedClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
      )
    },
  )

/**
 * 横划就把这一发手势吃掉,让内层的点击手势取消。
 * 单独导出是给「行里还套着别的点击目标」的场合用(整行走 [rowClickable] 就够了)。
 *
 * ## 认领与否必须等到 Final pass 再定(票 63)
 *
 * 这个 pointerInput 挂在**行**上,`change.position` 是行内局部坐标。列表跟手滚动时
 * 行随手指一起移动,于是局部 dy 恒 ≈ 0(手指在行自己的坐标系里没动),而列表不横滚,
 * 局部 dx 就是真实的横向漂移。拇指长拖天然带弧线,漂移一过 slop,
 * `abs(dx) > abs(dy)` 立刻成立 —— 原实现在这一刻误认领并消费之后每一发,
 * 纵向滚动的拖拽当场取消,**抬手前上滑下滑全部失效**(真机录屏逐帧实锤,
 * 见票 63:跟手 ≈15 发后 dx 累积过 slop,列表冻结,回拖同样无效)。
 *
 * 原来那句「认领前 `change.isConsumed` 就 break」防不住这个:它跑在 Initial pass,
 * 而纵向滚动在 **Main pass** 消费事件 —— 每一发新事件到 Initial 时消费位都是干净的,
 * 上一发被滚动吃掉这件事在 Initial 永远看不见。所以认领决策挪到同一发事件的
 * **Final pass**:滚动一旦消费过任何一发,这一把手势从此让位(横划取消点击的活,
 * 滚动的消费本身已经替我们干了)。认领后的消费仍在 Initial —— 要抢在内层
 * `clickable`(Main)看到事件之前。
 */
fun Modifier.cancelTapOnHorizontalDrag(enabled: Boolean = true): Modifier =
  if (!enabled) this else pointerInput(Unit) {
    val slopPx = viewConfiguration.touchSlop
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      var claimed = false
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (claimed) {
          // 连抬手那一发也消费:认领发生在上一发时,内层 clickable 还没见过
          // 任何被消费的事件,放过 up 它就会当成一次完整点击
          change.consume()
          if (!change.pressed) break
          continue
        }
        // 认领之前别人在 Initial 先动手了(抽屉的边缘手势):这一把不归我们
        if (change.isConsumed) break
        if (!change.pressed) break
        // 等这一发走完 Main:纵向滚动消费与否只有到 Final 才可见
        val settled = awaitPointerEvent(PointerEventPass.Final)
        val fin = settled.changes.firstOrNull { it.id == down.id } ?: break
        if (!fin.pressed) break
        when (rowTapStep(fin.isConsumed, fin.position.x - down.position.x, fin.position.y - down.position.y, slopPx)) {
          RowTapStep.YIELD -> break
          RowTapStep.CLAIM -> { claimed = true; fin.consume() }
          RowTapStep.WATCH -> Unit
        }
      }
    }
  }

/** 一发事件走完 Main pass 之后,这个手势该怎么走。 */
enum class RowTapStep {
  /** 还没定性,继续看下一发。 */
  WATCH,

  /** 滚动(或别的手势)已经消费过:这一把永远让位 —— 票 63 的修复位。 */
  YIELD,

  /** 横划坐实:从这一发起全部消费,取消内层点击(票 23)。 */
  CLAIM,
}

/**
 * [RowTapStep] 的裁决。消费检查在前:滚动接管后行会随手指移动,局部 dy 被清零,
 * 这时的 dx/dy 已经不描述手指的真实轨迹,不许再拿去做横划判定。
 */
fun rowTapStep(consumedByOthers: Boolean, dx: Float, dy: Float, slopPx: Float): RowTapStep = when {
  consumedByOthers -> RowTapStep.YIELD
  shouldCancelRowTap(dx, dy, slopPx) -> RowTapStep.CLAIM
  else -> RowTapStep.WATCH
}

/**
 * 这一次位移算不算「横划,不是点」。
 *
 * 两个条件都要满足:横向走够了 touch slop(手指按在屏上的抖动不算),
 * 且横向压过纵向(斜着滑向下一屏内容时该让给列表滚动,不是在这儿判)。
 * 与抽屉那处不同的是**这里不看方向** —— 往左往右都不该变成一次导航。
 */
fun shouldCancelRowTap(dx: Float, dy: Float, slopPx: Float): Boolean =
  abs(dx) > slopPx && abs(dx) > abs(dy)
