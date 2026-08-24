package com.chasel.ng2n.ui.drawer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 抽屉手势的阈值状态机。判据出处见 [DrawerGeometry] 的 KDoc(RN 侧 `ui/drawer.tsx`)。
 *
 * 这些数在真机上肉眼分不出来,只能靠这里钉死 —— 票 19 场景 6 拿抽屉逐帧对拍时,
 * 「感觉不对」要能回到具体某一条判据上。
 */
class DrawerGestureTest {

  /** 一屏 3 倍密度下的三个像素值(测试里固定,免得跟设备密度纠缠)。 */
  private val width = DrawerGeometry.WIDTH_DP * 3f
  private val edge = DrawerGeometry.EDGE_WIDTH_DP * 3f
  private val slop = DrawerGeometry.GESTURE_SLOP_DP * 3f

  // ---------------------------------------------------------------- 边缘判定

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

  // ---------------------------------------------------------------- 认领判定

  @Test
  fun 位移不到12dp一律不认() {
    assertFalse(shouldClaimDrawerDrag(slop, 0f, slop, allowOpen = true, allowClose = false))
    assertFalse(shouldClaimDrawerDrag(-slop, 0f, slop, allowOpen = false, allowClose = true))
    assertFalse(shouldClaimDrawerDrag(1f, 0f, slop, allowOpen = true, allowClose = true))
  }

  @Test
  fun 超过12dp的右滑在拉出档才认() {
    assertTrue(shouldClaimDrawerDrag(slop + 1f, 0f, slop, allowOpen = true, allowClose = false))
    // 面板上只接左滑,右滑不认(纵向与右向留给别人)
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
    // dy 恰好让 |dx| == |dy| * 1.3 —— 判据是严格大于,所以不认
    val dy = dx / DrawerGeometry.AXIS_RATIO
    assertFalse(shouldClaimDrawerDrag(dx, dy, slop, allowOpen = true, allowClose = false))
    assertTrue(shouldClaimDrawerDrag(dx, dy * 0.5f, slop, allowOpen = true, allowClose = false))
  }

  // ---------------------------------------------------------------- 跟手进度

  @Test
  fun 进度按位移除以面板宽_两头夹住() {
    assertEquals(0.5f, drawerProgress(0f, width / 2f, width))
    assertEquals(1f, drawerProgress(0f, width * 2f, width))
    assertEquals(0f, drawerProgress(0f, -10f, width))
    // 从开着的状态往左拖
    assertEquals(0.25f, drawerProgress(1f, -width * 0.75f, width))
  }

  // ---------------------------------------------------------------- 松手判定

  @Test
  fun 从关着拖过40percent就开() {
    // 不用 0.4f 这个正点值断言:Float 里 `1f - 0.6f` 是 0.39999998、`0.4f - 0f` 是 0.4,
    // 边界两侧各差半个 ulp,断在正点上等于断浮点噪声。判据本身是 `>= 0.4`
    assertTrue(settleDrawerOpen(startProgress = 0f, progress = 0.41f, velocityPxPerMs = 0f))
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.39f, velocityPxPerMs = 0f))
  }

  @Test
  fun 从开着拖回40percent就关() {
    // RN 版原判据:`-dx > DRAWER_WIDTH * 0.4`,即进度从 1 掉到 0.6 以下
    assertFalse(settleDrawerOpen(startProgress = 1f, progress = 0.59f, velocityPxPerMs = 0f))
    assertTrue(settleDrawerOpen(startProgress = 1f, progress = 0.61f, velocityPxPerMs = 0f))
  }

  @Test
  fun 甩得够快时速度说了算() {
    val fast = DrawerGeometry.COMMIT_VELOCITY + 0.01f
    // 只挪了一点点,但甩得快 → 开
    assertTrue(settleDrawerOpen(startProgress = 0f, progress = 0.05f, velocityPxPerMs = fast))
    // 已经拖到几乎全开,但往回甩 → 关
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.95f, velocityPxPerMs = -fast))
  }

  @Test
  fun 速度不到阈值时不算数() {
    val slow = DrawerGeometry.COMMIT_VELOCITY
    // 恰好等于阈值:判据是严格大于,所以退回看位移
    assertFalse(settleDrawerOpen(startProgress = 0f, progress = 0.05f, velocityPxPerMs = slow))
  }

  // ---------------------------------------------------------------- 遮罩几何(票 59)

  /** 富 trace 那台机器的屏宽:1220px。 */
  private val screen = 1220f

  @Test
  fun 遮罩左边界跟着面板右缘走() {
    // 面板右缘 = progress * widthPx;再往左让 1px 给抗锯齿那一列
    assertEquals(width * 0.5f - 1f, drawerScrimLeft(0.5f, width, screen))
    assertEquals(width - 1f, drawerScrimLeft(1f, width, screen))
  }

  @Test
  fun 开到底时省掉的正是被面板盖住的那块() {
    // 900px 面板 / 1220px 屏:剩下要画的只有 321px(含 1px 接缝保护)
    val left = drawerScrimLeft(1f, width, screen)
    assertEquals(899f, left)
    assertEquals(321f, screen - left)
    // 省下来的面积占比 —— 票 58 裁定第五节说的「约 74%」
    assertTrue(left / screen > 0.73f)
  }

  @Test
  fun 刚开始拉出时几乎整屏都要画() {
    // progress 极小 ⇒ 面板还基本在屏外,遮罩必须从 0 画起(负数要夹住)
    assertEquals(0f, drawerScrimLeft(0f, width, screen))
    assertEquals(0f, drawerScrimLeft(0.001f, width, screen))
  }

  @Test
  fun 面板比屏还宽时左边界不越过右边界() {
    // 窄屏(折叠屏外屏 / 分屏)上 300dp 可能比容器还宽,size 不能算成负数
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
  fun 时长开220关200() {
    assertEquals(220, DrawerGeometry.OPEN_DURATION_MS)
    assertEquals(200, DrawerGeometry.CLOSE_DURATION_MS)
    assertEquals(300f, DrawerGeometry.WIDTH_DP)
    assertEquals(22f, DrawerGeometry.EDGE_WIDTH_DP)
  }
}
