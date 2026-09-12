package com.chasel.ng2n.ui.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 57:下拉刷新状态的两条「什么都不做」判据。
 *
 * 它们决定了 `PullToRefreshModifierNode` 挂在列表 fling 路径上的那两处挂起调用
 * 会不会真的去碰 `Animatable`:
 *
 * - [pullToRefreshNeedsHide] 管 `onPreFling` → `onRelease` → `animateToHidden()`;
 * - [pullToRefreshNeedsSnap] 管 `onPostScroll` 每帧 `launch` 的那一发 `snapTo`。
 *
 * 真机上的因果(fling 晚起一帧 / `MutationInterruptedException` 掐掉整个 fling 协程)
 * 只有设备能验;能在 JVM 上钉死的是判据本身。
 */
class ListPullToRefreshTest {

  @Test
  fun `没下拉过就不该跑收回动画`() {
    // 向上快甩:指示器一次都没被拉出来,distanceFraction 全程 0
    assertFalse(pullToRefreshNeedsHide(distanceFraction = 0f, animating = false))
  }

  @Test
  fun `拉出来过就要收回去`() {
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 0.3f, animating = false))
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 1.4f, animating = false), "过冲也要收")
  }

  @Test
  fun `动画在跑时不能提前返回`() {
    // 归零动画自己正跑到一半时松手:直接 return 会把它留在半路上
    assertTrue(pullToRefreshNeedsHide(distanceFraction = 0f, animating = true))
  }

  @Test
  fun `同值 snapTo 是空操作`() {
    // 滚动中每一帧都会发一发 snapTo(0f),而值本来就是 0
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
    // `Animatable.snapTo` 的副作用之一是 endAnimation();值相同也不能省
    assertTrue(pullToRefreshNeedsSnap(current = 0f, target = 0f, animating = true))
  }
}
