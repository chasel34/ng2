package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListPullToRefreshTest {

  @Test
  fun `没下拉过就不该跑收回动画`() {
    assertFalse(pullToRefreshNeedsHide(distanceFraction = 0f, animating = false))
  }

  @Test
  fun `拉出来过就要收回去`() {
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 0.3f, animating = false))
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 1.4f, animating = false), "过冲也要收")
  }

  @Test
  fun `动画在跑时不能提前返回`() {
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 0f, animating = true))
  }

  @Test
  fun `同值 snapTo 是空操作`() {
    assertFalse(pullToRefreshNeedsSnap(current = 0f, target = 0f, animating = false))
    assertFalse(pullToRefreshNeedsSnap(current = 1f, target = 1f, animating = false))
  }

  @Test
  fun `值变了就要 snap`() {
    assertTrue(pullToRefreshNeedsSnap(current = 0f, target = 0.42f, animating = false))
    assertTrue(pullToRefreshNeedsSnap(current = 0.42f, target = 0f, animating = false))
  }

  @Test
  fun `snapTo 要顺带停掉在跑的动画`() {
    assertTrue(pullToRefreshNeedsSnap(current = 0f, target = 0f, animating = true))
  }
}
