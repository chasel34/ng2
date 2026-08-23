package com.chasel.ng2n.ui.common

import kotlin.test.Test
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
}
