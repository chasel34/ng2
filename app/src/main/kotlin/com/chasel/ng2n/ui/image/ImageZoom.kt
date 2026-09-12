package com.chasel.ng2n.ui.image

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

const val DOUBLE_TAP_SCALE: Float = 2.5f

const val MAX_SCALE: Float = 4f

private const val MIN_PINCH_SCALE = 0.6f

const val EDGE_RESISTANCE: Float = 0.55f
const val BOUNCE_MS: Int = 220

const val ZOOM_EPSILON: Float = 1.01f

val EaseStandard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

@Stable
class ZoomState {
  val scale = Animatable(1f)
  val offsetX = Animatable(0f)
  val offsetY = Animatable(0f)

  private var rawX = 0f
  private var rawY = 0f

  var containerSize by mutableStateOf(IntSize.Zero)

  var aspect by mutableFloatStateOf(0f)

  val zoomed: Boolean get() = scale.value > ZOOM_EPSILON

  val atFit: Boolean
    get() = scale.value <= ZOOM_EPSILON && atFitOffset(offsetX.value, offsetY.value - boundsFor(1f).y)

  fun boundsFor(value: Float): Offset =
    panBounds(containerSize.width.toFloat(), containerSize.height.toFloat(), aspect, value)

  private fun limits(): Offset = Offset(
    overshootLimit(containerSize.width.toFloat()),
    overshootLimit(containerSize.height.toFloat()),
  )

  suspend fun onGestureUpdate(centroid: Offset, pan: Offset, zoomChange: Float) {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val next = (scale.value * zoomChange).coerceIn(MIN_PINCH_SCALE, MAX_SCALE * 1.4f)
    val growth = if (scale.value <= 0f) 1f else next / scale.value
    val focalX = centroid.x - w / 2f
    val focalY = centroid.y - h / 2f
    val bounds = boundsFor(next)
    val limit = limits()
    rawX = nextRawPan(rawX, focalX, growth, pan.x, bounds.x, limit.x)
    rawY = nextRawPan(rawY, focalY, growth, pan.y, bounds.y, limit.y)
    scale.snapTo(next)
    offsetX.snapTo(rubberBand(rawX, bounds.x, limit.x))
    offsetY.snapTo(rubberBand(rawY, bounds.y, limit.y))
  }

  suspend fun settle() {
    val target = scale.value.coerceIn(1f, MAX_SCALE)
    val bounds = boundsFor(target)
    rawX = clampRawPan(rawX, bounds.x, 0f)
    rawY = clampRawPan(rawY, bounds.y, 0f)
    animateTogether(target, rawX, rawY)
  }

  private suspend fun animateTogether(targetScale: Float, targetX: Float, targetY: Float) {
    val spec = tween<Float>(BOUNCE_MS, easing = EaseStandard)
    coroutineScope {
      launch { scale.animateTo(targetScale, spec) }
      launch { offsetX.animateTo(targetX, spec) }
      launch { offsetY.animateTo(targetY, spec) }
    }
  }

  suspend fun toggleDoubleTap(tap: Offset) {
    if (!atFit) {
      reset(animated = true)
      return
    }
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val bounds = boundsFor(DOUBLE_TAP_SCALE)
    val current = if (scale.value <= 0f) 1f else scale.value
    val growth = DOUBLE_TAP_SCALE / current
    rawX = clampRawPan(focalZoomPan(rawX, tap.x - w / 2f, growth), bounds.x, 0f)
    rawY = clampRawPan(focalZoomPan(rawY, tap.y - h / 2f, growth), bounds.y, 0f)
    animateTogether(DOUBLE_TAP_SCALE, rawX, rawY)
  }

  suspend fun reset(animated: Boolean) {
    rawX = 0f
    rawY = boundsFor(1f).y
    if (!animated) {
      scale.snapTo(1f)
      offsetX.snapTo(0f)
      offsetY.snapTo(rawY)
      return
    }
    animateTogether(1f, 0f, rawY)
  }
}

suspend fun PointerInputScope.detectViewerTransform(
  isZoomed: () -> Boolean,
  canPanVertically: () -> Boolean = { false },
  onStart: () -> Unit,
  onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
  onEnd: () -> Unit,
) {
  awaitEachGesture {
    var zoomAccum = 1f
    var panAccum = Offset.Zero
    var pastSlop = false
    var started = false
    val slop = viewConfiguration.touchSlop

    awaitFirstDown(requireUnconsumed = false)
    do {
      val event = awaitPointerEvent()
      if (event.changes.any { it.isConsumed }) break

      val pressed = event.changes.count { it.pressed }
      if (pressed <= 1 && !isZoomed()) {
        if (!canPanVertically()) continue
        if (!pastSlop) {
          panAccum += event.calculatePan()
          if (panAccum.getDistance() < slop) continue
          if (abs(panAccum.x) >= abs(panAccum.y)) break
          pastSlop = true
        }
      }

      val zoomChange = event.calculateZoom()
      val panChange = event.calculatePan()
      if (!pastSlop) {
        zoomAccum *= zoomChange
        panAccum += panChange
        val centroidSize = event.calculateCentroidSize(useCurrent = false)
        val zoomMotion = abs(1f - zoomAccum) * centroidSize
        if (zoomMotion > slop || panAccum.getDistance() > slop) pastSlop = true
      }
      if (pastSlop) {
        if (!started) {
          onStart()
          started = true
        }
        val centroid = event.calculateCentroid(useCurrent = false)
        if (zoomChange != 1f || panChange != Offset.Zero) {
          onGesture(centroid, panChange, zoomChange)
        }
        event.changes.forEach { if (it.positionChanged()) it.consume() }
      }
    } while (event.changes.any { it.pressed })
    if (started) onEnd()
  }
}
