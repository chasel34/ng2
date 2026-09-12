package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowTapTest {

  private val slop = 24f

  @Test
  fun `横划过了 slop 就不算点这一行`() {
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
    assertFalse(shouldCancelRowTap(dx = 200f, dy = 300f, slopPx = slop))
  }

  @Test
  fun `斜着但横向占优 一样取消这一次点击`() {
    assertTrue(shouldCancelRowTap(dx = 300f, dy = 200f, slopPx = slop))
    assertTrue(shouldCancelRowTap(dx = -300f, dy = -200f, slopPx = slop))
  }

  @Test
  fun `滚动消费过就永远让位 哪怕局部坐标看着像横划`() {
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
    var dxLocal = 0f
    for (step in 1..46) {
      dxLocal += 3f
      val dyLocal = if (step <= 1) 30f else 28f
      val consumed = step > 1
      val verdict = rowTapStep(consumedByOthers = consumed, dx = dxLocal, dy = dyLocal, slopPx = slop)
      assertFalse(verdict == RowTapStep.CLAIM, "第 $step 发不该认领(dx=$dxLocal)")
    }
  }
}
