package com.chasel.ng2n.ui.drawer

import kotlin.math.abs

/**
 * 抽屉手势的**纯状态机** —— 阈值判定全在这里,Compose 那一层只负责喂坐标。
 *
 * 拆出来的理由:票 19 场景 6 要拿抽屉逐帧对拍,而「40% 还是 45%」「12dp 还是 8dp」
 * 这种判据在真机上肉眼分不出来、在 UI 测试里也难钉。做成纯 Kotlin 之后 JVM 单测
 * 能把每一条阈值钉死(`DrawerGestureTest`)。
 *
 * 数值全部照抄 RN 侧 `src/ui/drawer.tsx`:
 *
 * | 量 | 值 | RN 侧常量 |
 * |---|---|---|
 * | 面板宽 | 300dp | `DRAWER_WIDTH` |
 * | 左边缘拉出区 | 22dp | `DRAWER_EDGE_WIDTH`(导出给首页横滑让位) |
 * | 认领位移 | 12dp | `GESTURE_SLOP` |
 * | 认领的横纵比 | 1.3 | `Math.abs(dx) > Math.abs(dy) * 1.3` |
 * | 完成比例 | 40% | `COMMIT_RATIO` |
 * | 完成速度 | 0.5 px/ms | `COMMIT_VELOCITY` |
 * | 开 / 关时长 | 220 / 200ms | `duration.panel` / `duration.base` |
 */
object DrawerGeometry {
  const val WIDTH_DP = 300f
  const val EDGE_WIDTH_DP = 22f
  const val GESTURE_SLOP_DP = 12f
  const val AXIS_RATIO = 1.3f
  const val COMMIT_RATIO = 0.4f

  /** px/ms(RN 的 `gesture.vx` 就是这个单位;Compose 的 Velocity 是 px/s,除 1000)。 */
  const val COMMIT_VELOCITY = 0.5f

  const val OPEN_DURATION_MS = 220
  const val CLOSE_DURATION_MS = 200

  /**
   * 裁剪左边界往面板底下多吃的那 1px(票 59;遮罩与首页两刀共用)。
   *
   * 面板靠 `graphicsLayer{translationX}` 平移,右缘落在**小数像素**上,那一列是抗锯齿
   * 出来的半透明;裁剪自己的边界同样抗锯齿。左边界正好切在面板右缘的话,这一列的
   * 合成结果会从「首页 → 遮罩 → 面板边缘」变成「面板边缘」,差一个亚像素。往左多留
   * 1px 把整列重新压回满覆盖区里,代价是少省 1/1220 的面积。
   */
  const val SCRIM_SEAM_GUARD_PX = 1f
}

/** 起手点落在左边缘那一条里吗 —— 落在里面才归抽屉,外面归首页的横滑 pager。 */
fun isInDrawerEdge(x: Float, edgePx: Float): Boolean = x <= edgePx

/**
 * 这一次移动够不够格认领。
 *
 * [allowOpen] / [allowClose] 决定认哪个方向:边缘拉出只认右滑,面板上只认左滑
 * (纵向留给面板里的滚动)。横纵比那一档是防手抖:斜着划不该把抽屉扯出来。
 */
fun shouldClaimDrawerDrag(
  dx: Float,
  dy: Float,
  slopPx: Float,
  allowOpen: Boolean,
  allowClose: Boolean,
): Boolean {
  if (abs(dx) <= abs(dy) * DrawerGeometry.AXIS_RATIO) return false
  if (allowOpen && dx > slopPx) return true
  if (allowClose && dx < -slopPx) return true
  return false
}

/** 跟手位移 → 进度。0 = 全关,1 = 全开;两头夹住。 */
fun drawerProgress(startProgress: Float, dx: Float, widthPx: Float): Float {
  if (widthPx <= 0f) return startProgress
  return (startProgress + dx / widthPx).coerceIn(0f, 1f)
}

/**
 * **被面板整块盖住的那段的右边界**(px,容器左边界起算)—— 票 59 的 GPU 削减。
 *
 * 两个调用点共用它,论证是同一条:遮罩从这里开始画([DrawerHost] 的 `drawBehind`),
 * 首页也从这里开始画(二轮加的 `drawWithContent` + `clipRect`)。左边那一段两者都
 * 100% 被不透明面板盖掉,画了等于没画。
 *
 * 面板贴左摆、宽 [DrawerGeometry.WIDTH_DP],关着时靠 `translationX = -(1-progress)*width`
 * 平移出屏,所以它的右缘恒在 `progress * widthPx`。面板底色是 `colors.surface`
 * (三套配色全是 `0xFF…`,不透明),且**画在遮罩之上** —— 于是 `[0, 面板右缘)` 这一段
 * 遮罩每一帧都被整个盖掉,是纯 overdraw。
 *
 * 票 58 裁定第五节:抽屉链每帧 GPU 光栅是 tab 链的 4 倍(中位 5.3–6.4ms、p95 8.0–8.2ms),
 * 已经贴着 120Hz 的 8.333ms 预算,而单帧撑爆预算会把 SF 推进「多压一档 buffer」的粘滞态
 * (整段 trace 里只靠丢帧自愈过一次)。1220px 宽的屏上开到底时面板占 ~900px,
 * 这一刀省掉约 74% 的整屏 alpha 混合,**且被盖住的区域本来就看不见**。
 *
 * @param containerWidthPx 遮罩节点自己的宽(= 屏宽)
 * @return 左边界,已夹在 `[0, containerWidthPx]`;右边界恒为 `containerWidthPx`
 */
fun drawerScrimLeft(progress: Float, widthPx: Float, containerWidthPx: Float): Float {
  if (containerWidthPx <= 0f) return 0f
  val panelEdge = progress.coerceIn(0f, 1f) * widthPx
  return (panelEdge - DrawerGeometry.SCRIM_SEAM_GUARD_PX).coerceIn(0f, containerWidthPx)
}

/**
 * 松手后落到开还是关。
 *
 * 1. 甩得够快(±[DrawerGeometry.COMMIT_VELOCITY] px/ms)一律听速度 —— 快速轻扫
 *    走不满 40% 也该完成;
 * 2. 否则看**这一把走了多远**:从起手状态挪过 40% 就翻面,不到就弹回去。
 *
 * 第 2 条与 RN 版的关闭判据(`-dx > DRAWER_WIDTH * 0.4`,即从 1 掉到 0.6 以下)
 * 逐字等价;**打开那一侧是本票收紧的**:RN 的边缘手柄不跟手,松手时只看
 * `dx > 22`(拉出区宽度)就开,于是「轻轻蹭一下边缘」也会整屏弹出抽屉。
 * 票面写的是「40% 或速度阈值完成」,这里按票面统一成对称判据(见票 16 Comments)。
 */
fun settleDrawerOpen(startProgress: Float, progress: Float, velocityPxPerMs: Float): Boolean {
  if (velocityPxPerMs > DrawerGeometry.COMMIT_VELOCITY) return true
  if (velocityPxPerMs < -DrawerGeometry.COMMIT_VELOCITY) return false
  val travelled = abs(progress - startProgress)
  val flipped = travelled >= DrawerGeometry.COMMIT_RATIO
  val startedOpen = startProgress >= 0.5f
  return if (flipped) !startedOpen else startedOpen
}
