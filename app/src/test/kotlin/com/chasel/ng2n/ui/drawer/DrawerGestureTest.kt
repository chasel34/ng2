package com.chasel.ng2n.ui.drawer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawerGestureTest {

  private val width = DrawerGeometry.WIDTH_DP * 3f
  private val edge = DrawerGeometry.EDGE_WIDTH_DP * 3f
  private val slop = DrawerGeometry.GESTURE_SLOP_DP * 3f

  @Test
  fun 左边缘22dp以内起手归抽屉() {
    assertTrue(isInDrawerEdge(0f, edge))
    assertTrue(isInDrawerEdge(edge, edge))
  }

  @Test
  fun 边缘22dp以外起手不归抽屉_让给首页横滑() {
    assertFalse(isInDrawerEdge(edge + 0.5f, edge))
    assertFalse(isInDrawerEdge(400f, edge))
  }

  @Test
  fun 位移不到12dp一律不认() {
    assertFalse(shouldClaimDrawerDrag(slop, 0f, slop, allowOpen = true, allowClose = false))
    assertFalse(shouldClaimDrawerDrag(-slop, 0f, slop, allowOpen = false, allowClose = true))
    assertFalse(shouldClaimDrawerDrag(1f, 0f, slop, allowOpen = true, allowClose = true))
  }

  @Test
  fun 超过12dp的右滑在拉出档才认() {
    assertTrue(shouldClaimDrawerDrag(slop + 1f, 0f, slop, allowOpen = true, allowClose = false))
    assertFalse(shouldClaimDrawerDrag(slop + 1f, 0f, slop, allowOpen = false, allowClose = true))
  }

  @Test
  fun 超过12dp的左滑在关闭档才认() {
    assertTrue(shouldClaimDrawerDrag(-slop - 1f, 0f, slop, allowOpen = false, allowClose = true))
    assertFalse(shouldClaimDrawerDrag(-slop - 1f, 0f, slop, allowOpen = true, allowClose = false))
  }

  @Test
  fun 斜着划不认_横纵比要过13倍() {
    val dx = slop + 20f
    val dy = dx / DrawerGeometry.AXIS_RATIO
    assertFalse(shouldClaimDrawerDrag(dx, dy, slop, allowOpen = true, allowClose = false))
    assertTrue(shouldClaimDrawerDrag(dx, dy * 0.5f, slop, allowOpen = true, allowClose = false))
  }

  @Test
  fun 进度按位移除以面板宽_两头夹住() {
    assertEquals(0.5f, drawerProgress(0f, width / 2f, width))
    assertEquals(1f, drawerProgress(0f, width * 2f, width))
    assertEquals(0f, drawerProgress(0f, -10f, width))
    assertEquals(0.25f, drawerProgress(1f, -width * 0.75f, width))
  }

  @Test
  fun 从关着拖过40percent就开() {
    assertTrue(settleDrawerOpen(startProgress = 0f, progress = 0.41f, velocityPxPerMs = 0f))
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.39f, velocityPxPerMs = 0f))
  }

  @Test
  fun 从开着拖回40percent就关() {
    assertFalse(settleDrawerOpen(startProgress = 1f, progress = 0.59f, velocityPxPerMs = 0f))
    assertTrue(settleDrawerOpen(startProgress = 1f, progress = 0.61f, velocityPxPerMs = 0f))
  }

  @Test
  fun 甩得够快时速度说了算() {
    val fast = DrawerGeometry.COMMIT_VELOCITY + 0.01f
    assertTrue(settleDrawerOpen(startProgress = 0f, progress = 0.05f, velocityPxPerMs = fast))
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.95f, velocityPxPerMs = -fast))
  }

  @Test
  fun 速度不到阈值时不算数() {
    val slow = DrawerGeometry.COMMIT_VELOCITY
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.05f, velocityPxPerMs = slow))
  }

  private val screen = 1220f

  @Test
  fun 遮罩左边界跟着面板右缘走() {
    assertEquals(width * 0.5f - 1f, drawerScrimLeft(0.5f, width, screen))
    assertEquals(width - 1f, drawerScrimLeft(1f, width, screen))
  }

  @Test
  fun 开到底时省掉的正是被面板盖住的那块() {
    val left = drawerScrimLeft(1f, width, screen)
    assertEquals(899f, left)
    assertEquals(321f, screen - left)
    assertTrue(left / screen > 0.73f)
  }

  @Test
  fun 刚开始拉出时几乎整屏都要画() {
    assertEquals(0f, drawerScrimLeft(0f, width, screen))
    assertEquals(0f, drawerScrimLeft(0.001f, width, screen))
  }

  @Test
  fun 面板比屏还宽时左边界不越过右边界() {
    val narrow = 600f
    assertEquals(narrow, drawerScrimLeft(1f, width, narrow))
    assertTrue(narrow - drawerScrimLeft(1f, width, narrow) >= 0f)
  }

  @Test
  fun 容器还没测量出来时不画到屏外() {
    assertEquals(0f, drawerScrimLeft(1f, width, 0f))
    assertEquals(0f, drawerScrimLeft(1f, width, -10f))
  }

  @Test
  fun progress越界一律夹回0到1() {
    assertEquals(0f, drawerScrimLeft(-0.5f, width, screen))
    assertEquals(drawerScrimLeft(1f, width, screen), drawerScrimLeft(1.5f, width, screen))
  }

  @Test
  fun 裁剪边界恒不越过面板右缘() {
    for (step in 0..100) {
      val progress = step / 100f
      val panelEdge = progress * width
      val left = drawerScrimLeft(progress, width, screen)
      assertTrue(left <= panelEdge, "progress=$progress 时裁到了 $left,面板右缘才 $panelEdge")
    }
  }

  @Test
  fun 裁剪边界随进度单调不减() {
    var previous = -1f
    for (step in 0..100) {
      val left = drawerScrimLeft(step / 100f, width, screen)
      assertTrue(left >= previous, "progress=${step / 100f} 时边界从 $previous 退回 $left")
      previous = left
    }
  }

  @Test
  fun 时长开220关200() {
    assertEquals(220, DrawerGeometry.OPEN_DURATION_MS)
    assertEquals(200, DrawerGeometry.CLOSE_DURATION_MS)
    assertEquals(300f, DrawerGeometry.WIDTH_DP)
    assertEquals(22f, DrawerGeometry.EDGE_WIDTH_DP)
  }
}
