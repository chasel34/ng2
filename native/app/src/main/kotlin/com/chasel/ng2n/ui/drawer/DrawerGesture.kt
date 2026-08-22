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
