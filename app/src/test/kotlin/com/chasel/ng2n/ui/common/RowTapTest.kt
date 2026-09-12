package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 23:列表行的「这是横划,不是点」判据([shouldCancelRowTap])。
 *
 * 手势的接线(Initial 通道 + `consume`)只有真机/模拟器能验;能在 JVM 上钉死的是
 * 判据本身 —— 它决定了「横划一把会不会跳进落点那一行」。
 * 阈值取 Android 默认 touch slop 的量级(24dp 屏上约 8dp ≈ 24px)。
 */
class RowTapTest {

  private val slop = 24f

  @Test
  fun `横划过了 slop 就不算点这一行`() {
    // 复现票 23:`input swipe 900 1200 200 1200` —— 纯横向 700px
    assertTrue(shouldCancelRowTap(dx = -700f, dy = 0f, slopPx = slop))
    assertTrue(shouldCancelRowTap(dx = 700f, dy = 0f, slopPx = slop))
  }

  @Test
  fun `手指按着不动的抖动仍然是一次点击`() {
    assertFalse(shouldCancelRowTap(dx = 0f, dy = 0f, slopPx = slop))
    assertFalse(shouldCancelRowTap(dx = slop, dy = 0f, slopPx = slop), "正好等于 slop 还不算")
    assertFalse(shouldCancelRowTap(dx = -8f, dy = 3f, slopPx = slop))
  }

  @Test
  fun `纵向滚动不归这里管 —— 那是列表自己的事`() {
    assertFalse(shouldCancelRowTap(dx = 0f, dy = 400f, slopPx = slop))
    // 斜着但纵向占优:让给列表滚动,行的点击本来也会被列表消费掉
    assertFalse(shouldCancelRowTap(dx = 200f, dy = 300f, slopPx = slop))
  }

  @Test
  fun `斜着但横向占优 一样取消这一次点击`() {
    assertTrue(shouldCancelRowTap(dx = 300f, dy = 200f, slopPx = slop))
    assertTrue(shouldCancelRowTap(dx = -300f, dy = -200f, slopPx = slop))
  }

  // ---------------------------------------------------------------- 票 63:让位裁决

  @Test
  fun `滚动消费过就永远让位 哪怕局部坐标看着像横划`() {
    // 票 63 现场:列表跟手,行随手指移动,局部 dy 被清成 ≈0;拇指弧线漂移让
    // dx 越过 slop。此时事件已被纵向滚动在 Main 消费 —— 必须 YIELD,不许 CLAIM。
    assertEquals(RowTapStep.YIELD, rowTapStep(consumedByOthers = true, dx = 45f, dy = 2f, slopPx = slop))
    assertEquals(RowTapStep.YIELD, rowTapStep(consumedByOthers = true, dx = 700f, dy = 0f, slopPx = slop))
  }

  @Test
  fun `没人消费时才轮到横划判据`() {
    assertEquals(RowTapStep.CLAIM, rowTapStep(consumedByOthers = false, dx = 700f, dy = 0f, slopPx = slop))
    assertEquals(RowTapStep.WATCH, rowTapStep(consumedByOthers = false, dx = 8f, dy = 3f, slopPx = slop))
    assertEquals(RowTapStep.WATCH, rowTapStep(consumedByOthers = false, dx = 200f, dy = 300f, slopPx = slop))
  }

  @Test
  fun `票63 真机事件序列回放 全程不许认领`() {
    // rec4 实录形态:30px/发 的纵向拖 + 3px/发 横向漂移。滚动从越过纵向 slop 那一发
    // 起消费每一发;行随滚动移动,局部 dy 停在「一发的滞后」量级(≈30px)。
    // 旧实现在 dx 累到 45px(第 15 发)误认领 → 列表冻结;新裁决必须一路让位。
    var dxLocal = 0f
    for (step in 1..46) {
      dxLocal += 3f
      val dyLocal = if (step <= 1) 30f else 28f // 滚动接管后局部 dy 只剩跟踪滞后
      val consumed = step > 1 // 第一发过 slop,滚动从第二发起消费
      val verdict = rowTapStep(consumedByOthers = consumed, dx = dxLocal, dy = dyLocal, slopPx = slop)
      assertFalse(verdict == RowTapStep.CLAIM, "第 $step 发不该认领(dx=$dxLocal)")
    }
  }
}
