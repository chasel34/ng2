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
 * - Animatable 里的是**显示值** = `rubberBand(raw)`(越界打阻尼,且阻尼有上限)。
 *
 * 分开是因为阻尼必须作用在「起点 + 本次总位移」上,而不是逐帧作用在上一帧的结果上——
 * 后者会把阻尼复利成「越拖越拖不动然后突然不动了」。RN 侧靠 `startImageX + translationX`
 * 每帧重算达到同一效果。
 *
 * **边界口径全在 `ImageZoomMath.kt` 那组纯函数里**([panBounds] / [rubberBand] /
 * [nextRawPan]):票 55 之后 raw 与显示值在每一帧都被钳在「边界 + 越界额度」内,
 * 「图整体移出视口只剩黑屏」这个状态因此不可达 —— 不再指望松手那一下的 [settle] 去救。
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
   * 是否已经在「适配视口」这一档:缩放回到 1 **且**长图回到顶部、其他图居中。
   *
   * 双击靠它决定「放大」还是「复位」。只看缩放是不够的(票 55):缩放回到 1 而
   * 位移没回零的状态是存在的 —— 回弹动画被下一条手势抢断就是 ——
   * 那时候再双击若走「放大」分支,就把一个本来就偏的位置又放大一遍,
   * 用户会觉得「双击复位不管用」。
   */
  val atFit: Boolean
    get() = scale.value <= ZOOM_EPSILON && atFitOffset(offsetX.value, offsetY.value - boundsFor(1f).y)

  /**
   * 当前缩放下图片能被拖多远(中心系)。按屏宽适配画出来的尺寸由宽高比定,
   * 不是容器尺寸 —— 拿容器算的话竖图左右会多出两条拖得动的空白。
   *
   * 数学在 [panBounds],这里只把容器尺寸喂进去。
   */
  fun boundsFor(value: Float): Offset =
    panBounds(containerSize.width.toFloat(), containerSize.height.toFloat(), aspect, value)

  /** 两轴各自的越界额度(见 [overshootLimit])。 */
  private fun limits(): Offset = Offset(
    overshootLimit(containerSize.width.toFloat()),
    overshootLimit(containerSize.height.toFloat()),
  )

  suspend fun onGestureUpdate(centroid: Offset, pan: Offset, zoomChange: Float) {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val next = (scale.value * zoomChange).coerceIn(MIN_PINCH_SCALE, MAX_SCALE * 1.4f)
    val growth = if (scale.value <= 0f) 1f else next / scale.value
    // 焦点换到中心系,缩放期间钉住它(两指中点跟手)
    val focalX = centroid.x - w / 2f
    val focalY = centroid.y - h / 2f
    // 边界按**这一帧之后**的缩放算:缩小的那一帧当场重钳,否则缩回去会残留大位移(票 55)
    val bounds = boundsFor(next)
    val limit = limits()
    rawX = nextRawPan(rawX, focalX, growth, pan.x, bounds.x, limit.x)
    rawY = nextRawPan(rawY, focalY, growth, pan.y, bounds.y, limit.y)
    scale.snapTo(next)
    offsetX.snapTo(rubberBand(rawX, bounds.x, limit.x))
    offsetY.snapTo(rubberBand(rawY, bounds.y, limit.y))
  }

  /** 松手钳进当前倍率边界；长图在 1 倍下也保留纵向阅读位置。 */
  suspend fun settle() {
    val target = scale.value.coerceIn(1f, MAX_SCALE)
    val bounds = boundsFor(target)
    // limit = 0:松手就没有越界额度了,一路钳到边界对齐
    rawX = clampRawPan(rawX, bounds.x, 0f)
    rawY = clampRawPan(rawY, bounds.y, 0f)
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
   * 双击:**不在适配位就一定回适配位**,已经在适配位才以点按处为锚放大到
   * [DOUBLE_TAP_SCALE]。
   *
   * 判据用 [atFit] 而不是 [zoomed](票 55):从任意 scale/offset 状态双击都能回到
   * 适配位,是这一屏唯一的「逃生口」,不能有它落不到的状态。
   *
   * 放大用的是通用的焦点锚定式 [focalZoomPan](`growth = 目标/当前`),不再假定
   * 起点一定是 scale=1 / offset=0。
   */
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

  /**
   * 回适配位。`animated = false` 走 snap(换页兜底);`true` 走 220ms 回弹。
   *
   * 累计位移先回默认位置再动画:动画哪怕被下一条手势抢断,raw 也已经是默认位置,
   * 下一次手势不会从一个越界值继续累计(票 55 黑屏的成因之一)。
   */
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

/**
 * 查看器的核心手势:**同一条 Pan 按当前缩放拆两路**。
 *
 * - 原始大小 + 单指横拖 → 交给外层 `HorizontalPager` 翻页；长图纵拖阅读图片;
 * - 放大后单指 → 拖的是图,钳在图的边界内、越界给阻尼;
 * - 任意时刻双指 → 捏合缩放(顺带跟手平移,系统相册就是这个手感)。
 *
 * 要翻页得先双击/捏合回到原始大小,这也是系统相册的行为。RN 侧是靠
 * `Gesture.Pan().onUpdate` 里一个 `if (scale > ZOOM_EPSILON)` 分流达到同样效果。
 */
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
      // 外层(Pager)已经接管了这条手势就撒手
      if (event.changes.any { it.isConsumed }) break

      val pressed = event.changes.count { it.pressed }
      // 单指 + 没放大 = 这是翻页手势,不碰
      if (pressed <= 1 && !isZoomed()) {
        if (!canPanVertically()) continue
        // 长图在默认倍率下也能纵向读图；横向仍让 Pager 翻页。
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
