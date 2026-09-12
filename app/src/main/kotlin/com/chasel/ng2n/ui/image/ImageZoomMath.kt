package com.chasel.ng2n.ui.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

const val MAX_OVERSHOOT_FRACTION: Float = 0.15f

private const val FINITE_OVERSHOOT_CEILING = 1e30f

const val FIT_OFFSET_EPSILON: Float = 0.5f

fun fitDrawnSize(containerWidth: Float, containerHeight: Float, aspect: Float): Size {
  if (containerWidth <= 0f || containerHeight <= 0f) return Size.Zero
  if (aspect <= 0f || !aspect.isFinite()) return Size(containerWidth, containerHeight)
  return Size(containerWidth, containerWidth / aspect)
}

fun widthFitScale(containerWidth: Float, containerHeight: Float, aspect: Float): Float {
  if (containerWidth <= 0f || containerHeight <= 0f || aspect <= 0f || !aspect.isFinite()) return 1f
  return max(1f, containerWidth / (containerHeight * aspect))
}

fun panBounds(containerWidth: Float, containerHeight: Float, aspect: Float, scale: Float): Offset {
  val drawn = fitDrawnSize(containerWidth, containerHeight, aspect)
  if (drawn.width <= 0f || drawn.height <= 0f) return Offset.Zero
  val safeScale = max(0f, scale)
  return Offset(
    max(0f, (drawn.width * safeScale - containerWidth) / 2f),
    max(0f, (drawn.height * safeScale - containerHeight) / 2f),
  )
}

fun overshootLimit(containerExtent: Float): Float = max(0f, containerExtent) * MAX_OVERSHOOT_FRACTION

fun clampRawPan(value: Float, bound: Float, limit: Float): Float {
  if (value.isNaN()) return 0f
  val edge = max(0f, bound) + max(0f, limit)
  return value.coerceIn(-edge, edge)
}

fun rubberBand(value: Float, bound: Float, limit: Float): Float {
  if (value.isNaN()) return 0f
  val edge = max(0f, bound)
  val cap = max(0f, limit)
  val over = (abs(value) - edge).coerceAtMost(FINITE_OVERSHOOT_CEILING)
  if (over <= 0f) return value
  if (cap <= 0f) return sign(value) * edge
  val damped = cap * over * EDGE_RESISTANCE / (cap + over * EDGE_RESISTANCE)
  return sign(value) * (edge + damped)
}

fun focalZoomPan(current: Float, focal: Float, growth: Float): Float {
  if (!growth.isFinite() || growth <= 0f) return current
  return focal - (focal - current) * growth
}

fun nextRawPan(
  current: Float,
  focal: Float,
  growth: Float,
  pan: Float,
  bound: Float,
  limit: Float,
): Float = clampRawPan(focalZoomPan(current, focal, growth) + pan, bound, limit)

fun atFitOffset(x: Float, y: Float): Boolean =
  abs(x) <= FIT_OFFSET_EPSILON && abs(y) <= FIT_OFFSET_EPSILON
