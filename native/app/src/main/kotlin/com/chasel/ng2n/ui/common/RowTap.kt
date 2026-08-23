package com.chasel.ng2n.ui.common

import androidx.compose.foundation.clickable
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
fun Modifier.rowClickable(
  onClickLabel: String? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
): Modifier = this
  .cancelTapOnHorizontalDrag(enabled)
  .clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)

/**
 * 横划就把这一发手势吃掉,让内层的点击手势取消。
 * 单独导出是给「行里还套着别的点击目标」的场合用(整行走 [rowClickable] 就够了)。
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
        // 认领之前别人先动手了(抽屉的边缘手势、列表的纵向滚动):这一把不归我们
        if (!claimed && change.isConsumed) break
        if (!change.pressed) break
        if (!claimed) {
          val dx = change.position.x - down.position.x
          val dy = change.position.y - down.position.y
          if (!shouldCancelRowTap(dx, dy, slopPx)) continue
          claimed = true
        }
        change.consume()
      }
    }
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
