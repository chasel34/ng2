package com.chasel.ng2n.ui.drawer

import kotlin.math.abs

object DrawerGeometry {
  const val WIDTH_DP = 300f
  const val EDGE_WIDTH_DP = 22f
  const val GESTURE_SLOP_DP = 12f
  const val AXIS_RATIO = 1.3f
  const val COMMIT_RATIO = 0.4f

  /** 单位为 px/ms。 */
  const val COMMIT_VELOCITY = 0.5f

  const val OPEN_DURATION_MS = 220
  const val CLOSE_DURATION_MS = 200

  const val SCRIM_SEAM_GUARD_PX = 1f
}

fun isInDrawerEdge(x: Float, edgePx: Float): Boolean = x <= edgePx

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

fun drawerProgress(startProgress: Float, dx: Float, widthPx: Float): Float {
  if (widthPx <= 0f) return startProgress
  return (startProgress + dx / widthPx).coerceIn(0f, 1f)
}

fun drawerScrimLeft(progress: Float, widthPx: Float, containerWidthPx: Float): Float {
  if (containerWidthPx <= 0f) return 0f
  val panelEdge = progress.coerceIn(0f, 1f) * widthPx
  return (panelEdge - DrawerGeometry.SCRIM_SEAM_GUARD_PX).coerceIn(0f, containerWidthPx)
}

fun settleDrawerOpen(startProgress: Float, progress: Float, velocityPxPerMs: Float): Boolean {
  if (velocityPxPerMs > DrawerGeometry.COMMIT_VELOCITY) return true
  if (velocityPxPerMs < -DrawerGeometry.COMMIT_VELOCITY) return false
  val travelled = abs(progress - startProgress)
  val flipped = travelled >= DrawerGeometry.COMMIT_RATIO
  val startedOpen = startProgress >= 0.5f
  return if (flipped) !startedOpen else startedOpen
}
