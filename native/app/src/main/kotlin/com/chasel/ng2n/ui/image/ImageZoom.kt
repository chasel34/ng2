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
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 双击放大到的倍数(约等于系统相册那一档)。 */
const val DOUBLE_TAP_SCALE: Float = 2.5f

/** 捏合的上限;过程里允许暂时超出,松手弹回。 */
const val MAX_SCALE: Float = 4f

/** 捏合过程里允许掐到的最小值(完全掐死会显得僵),松手弹回 1。 */
private const val MIN_PINCH_SCALE = 0.6f

/** 拖过边界后的阻尼系数与回弹时长(RN 侧 `image-gallery.tsx:50-51` 同值)。 */
const val EDGE_RESISTANCE: Float = 0.55f
const val BOUNCE_MS: Int = 220

/** 缩放判定的容差:浮点回到 1 附近就算「原始大小」。 */
const val ZOOM_EPSILON: Float = 1.01f

/**
 * CSS `ease`。RN 侧 `motion.ts` 把设计稿的 `cubic-bezier(.25,.1,.25,1)` 显式复刻了一份,
 * 这里同值 —— 回弹与双击都用它。
 */
val EaseStandard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * 查看器里一张图的缩放/位移状态。
 *
 * 位移分**两份**:
 * - `rawX/rawY` 是没打折的累计位移(手指真正走了多远);
 * - Animatable 里的是**显示值** = `rubber(raw)`。
 *
 * 分开是因为阻尼必须作用在「起点 + 本次总位移」上,而不是逐帧作用在上一帧的结果上——
 * 后者会把阻尼复利成「越拖越拖不动然后突然不动了」。RN 侧靠 `startImageX + translationX`
 * 每帧重算达到同一效果。
 */
@Stable
class ZoomState {
  val scale = Animatable(1f)
  val offsetX = Animatable(0f)
  val offsetY = Animatable(0f)

  private var rawX = 0f
  private var rawY = 0f

  var containerSize by mutableStateOf(IntSize.Zero)

  /** 画出来那张图的宽高比;0 = 还没加载完,按容器算。 */
  var aspect by mutableFloatStateOf(0f)

  val zoomed: Boolean get() = scale.value > ZOOM_EPSILON

  /**
   * 当前缩放下图片能被拖多远(中心系)。`Fit` 画出来的尺寸由宽高比定,
   * 不是容器尺寸 —— 拿容器算的话竖图左右会多出两条拖得动的空白。
   */
  fun boundsFor(value: Float): Offset {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    if (w <= 0f || h <= 0f) return Offset.Zero
    var drawnWidth = w
    var drawnHeight = h
    if (aspect > 0f) {
      drawnWidth = min(w, h * aspect)
      drawnHeight = drawnWidth / aspect
    }
    return Offset(
      max(0f, (drawnWidth * value - w) / 2f),
      max(0f, (drawnHeight * value - h) / 2f),
    )
  }

  suspend fun onGestureUpdate(centroid: Offset, pan: Offset, zoomChange: Float) {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val next = (scale.value * zoomChange).coerceIn(MIN_PINCH_SCALE, MAX_SCALE * 1.4f)
    val growth = if (scale.value == 0f) 1f else next / scale.value
    // 焦点换到中心系,缩放期间钉住它(两指中点跟手)
    val focalX = centroid.x - w / 2f
    val focalY = centroid.y - h / 2f
    rawX = focalX - (focalX - rawX) * growth + pan.x
    rawY = focalY - (focalY - rawY) * growth + pan.y
    val bounds = boundsFor(next)
    scale.snapTo(next)
    offsetX.snapTo(rubber(rawX, -bounds.x, bounds.x))
    offsetY.snapTo(rubber(rawY, -bounds.y, bounds.y))
  }

  /** 松手:掐回原始大小就归零,否则钳进边界并回弹。 */
  suspend fun settle() {
    if (scale.value <= ZOOM_EPSILON) {
      reset(animated = true)
      return
    }
    val target = min(scale.value, MAX_SCALE)
    val bounds = boundsFor(target)
    rawX = rawX.coerceIn(-bounds.x, bounds.x)
    rawY = rawY.coerceIn(-bounds.y, bounds.y)
    animateTogether(target, rawX, rawY)
  }

  /**
   * 三条 Animatable 一起跑。
   *
   * `animateTo` 是挂起函数,顺着写就变成串行 —— 缩放 220ms 跑完才轮到位移,总时长
   * 三倍,观感是「先缩放再平移」两段动作。2026-08-22 模拟器实测抓到过:松手 450ms 后
   * 纵向回弹只走了 44%(下沿 1906 而不是 2029)。RN 侧三个 `withTiming` 天然并行,
   * 这里必须显式 launch 才对得上。
   */
  private suspend fun animateTogether(targetScale: Float, targetX: Float, targetY: Float) {
    val spec = tween<Float>(BOUNCE_MS, easing = EaseStandard)
    coroutineScope {
      launch { scale.animateTo(targetScale, spec) }
      launch { offsetX.animateTo(targetX, spec) }
      launch { offsetY.animateTo(targetY, spec) }
    }
  }

  /**
   * 双击:放大到点按处再钳进边界;已经放大了就还原。
   * 缩放围绕中心,所以把点按点平移到中心附近(`focal * (1 - scale)`)。
   */
  suspend fun toggleDoubleTap(tap: Offset) {
    if (zoomed) {
      reset(animated = true)
      return
    }
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val bounds = boundsFor(DOUBLE_TAP_SCALE)
    val focalX = tap.x - w / 2f
    val focalY = tap.y - h / 2f
    rawX = (focalX * (1f - DOUBLE_TAP_SCALE)).coerceIn(-bounds.x, bounds.x)
    rawY = (focalY * (1f - DOUBLE_TAP_SCALE)).coerceIn(-bounds.y, bounds.y)
    animateTogether(DOUBLE_TAP_SCALE, rawX, rawY)
  }

  suspend fun reset(animated: Boolean) {
    rawX = 0f
    rawY = 0f
    if (!animated) {
      scale.snapTo(1f)
      offsetX.snapTo(0f)
      offsetY.snapTo(0f)
      return
    }
    animateTogether(1f, 0f, 0f)
  }
}

/** 拉得动但拉不走:越界部分乘阻尼。 */
fun rubber(value: Float, min: Float, max: Float): Float = when {
  value < min -> min + (value - min) * EDGE_RESISTANCE
  value > max -> max + (value - max) * EDGE_RESISTANCE
  else -> value
}

/**
 * 查看器的核心手势:**同一条 Pan 按当前缩放拆两路**。
 *
 * - 原始大小 + 单指 → **一个事件都不消费**,交给外层 `HorizontalPager` 去翻页;
 * - 放大后单指 → 拖的是图,钳在图的边界内、越界给阻尼;
 * - 任意时刻双指 → 捏合缩放(顺带跟手平移,系统相册就是这个手感)。
 *
 * 要翻页得先双击/捏合回到原始大小,这也是系统相册的行为。RN 侧是靠
 * `Gesture.Pan().onUpdate` 里一个 `if (scale > ZOOM_EPSILON)` 分流达到同样效果。
 */
suspend fun PointerInputScope.detectViewerTransform(
  isZoomed: () -> Boolean,
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
      // 外层(Pager)已经接管了这条手势就撒手
      if (event.changes.any { it.isConsumed }) break

      val pressed = event.changes.count { it.pressed }
      // 单指 + 没放大 = 这是翻页手势,不碰
      if (pressed <= 1 && !isZoomed()) continue

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
