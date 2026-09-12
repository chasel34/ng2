package com.chasel.ng2n.ui.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * 大图查看器的边界数学(票 55)。
 *
 * 全是**纯函数**:只吃视口尺寸 / 宽高比 / 缩放 / 位移,不碰 Compose 状态,
 * 也不碰协程 —— 因为这一层出错的代价是「图整体移出视口只剩黑屏」,必须能在
 * JVM 单测里把各象限越界、极端宽高比一条条钉死(`ImageZoomMathTest`)。
 *
 * 坐标一律是**中心系**:0 = 图心与视口心重合,正 X 向右、正 Y 向下,单位 px。
 * 这与 `graphicsLayer` 的 `translationX/Y` 同系,所以状态里的值可以直接喂给它。
 *
 * ## 票 55 的教训
 *
 * 原实现只在**松手**(`settle`)时把累计位移钳回边界,手势过程中的越界靠
 * [EDGE_RESISTANCE] 打 55% 折 —— 而 55% 的折扣是**无上限**的:拖得够远
 * (或几次拖动之间的 `settle` 因为动画被后一条手势抢断而没兜住),显示位移就能
 * 大过「图半幅 + 视口半幅」,视口里一个像素的图都不剩,只剩底色。真机验收
 * 录到的 27 秒纯黑就是这个。
 *
 * 所以现在的口径是:**任何一个会被写进 `graphicsLayer` 的位移值都必须是有界的**。
 * 越界仍然拖得动(手感不能丢),但阻尼有渐近上限 [MAX_OVERSHOOT_FRACTION],
 * 且累计位移本身([clampRawPan])也在同一档上被硬钳。这样即使动画被抢断、
 * 或几条手势协程乱序落地,任何时刻的取值都还在「视口里必有图」的集合里 ——
 * 不再依赖「松手那一下一定跑到」这个前提。
 */

/**
 * 越界时最多允许露出的空白:视口该轴长度的这个比例。
 *
 * 0.15 是保守取值 —— 边缘对齐之后再多拖出去的空白不超过视口的 15%,也就是
 * 任意时刻视口里至少还有 85% 的位置被图盖着(图比视口小的那一轴同理:锁死居中
 * 之后最多偏 15% 视口长度,而图心离视口心不到半个视口,必然还在里面)。
 *
 * 注意这只是**累计位移**的硬上限;打完阻尼真正显示出来的越界还要更小
 * (见 [rubberBand],上限约 0.35×)。
 */
const val MAX_OVERSHOOT_FRACTION: Float = 0.15f

/** [rubberBand] 里给越界量的封顶,纯粹为了挡住 ±Infinity 参与运算。 */
private const val FINITE_OVERSHOOT_CEILING = 1e30f

/** [atFitOffset] 的容差:浮点残渣不算「没回到适配位」。 */
const val FIT_OFFSET_EPSILON: Float = 0.5f

/** 按屏宽适配的实际图幅；长图高度可以超过视口。 */
fun fitDrawnSize(containerWidth: Float, containerHeight: Float, aspect: Float): Size {
  if (containerWidth <= 0f || containerHeight <= 0f) return Size.Zero
  if (aspect <= 0f || !aspect.isFinite()) return Size(containerWidth, containerHeight)
  return Size(containerWidth, containerWidth / aspect)
}

/** 把 Fit 绘制的完整图片放大到屏宽，不在绘制阶段裁掉长图。 */
fun widthFitScale(containerWidth: Float, containerHeight: Float, aspect: Float): Float {
  if (containerWidth <= 0f || containerHeight <= 0f || aspect <= 0f || !aspect.isFinite()) return 1f
  return max(1f, containerWidth / (containerHeight * aspect))
}

/**
 * 某个缩放档位下,位移的**边界对齐**上界(中心系半幅,两轴各一)。
 *
 * - 这一轴上图比视口大 → 上界 = (图幅 - 视口幅) / 2,即拖到图的这条边与视口这条边贴齐;
 * - 这一轴上图比视口小 → 上界 = 0,**锁死居中**(系统相册就是这个手感,
 *   竖图放大后左右不该能晃)。
 */
fun panBounds(containerWidth: Float, containerHeight: Float, aspect: Float, scale: Float): Offset {
  val drawn = fitDrawnSize(containerWidth, containerHeight, aspect)
  if (drawn.width <= 0f || drawn.height <= 0f) return Offset.Zero
  val safeScale = max(0f, scale)
  return Offset(
    max(0f, (drawn.width * safeScale - containerWidth) / 2f),
    max(0f, (drawn.height * safeScale - containerHeight) / 2f),
  )
}

/** 这一轴允许的越界额度(见 [MAX_OVERSHOOT_FRACTION])。视口没量出来时是 0:先锁死不许动。 */
fun overshootLimit(containerExtent: Float): Float = max(0f, containerExtent) * MAX_OVERSHOOT_FRACTION

/**
 * 把**累计**位移钳进「边界 + 越界额度」。
 *
 * 钳的是 raw 而不只是显示值:一来手指回拖时立刻跟手(不用先把多拖的那截倒回来),
 * 二来它给了「写进 graphicsLayer 的值有界」这条性质一个不依赖时序的保证。
 */
fun clampRawPan(value: Float, bound: Float, limit: Float): Float {
  if (value.isNaN()) return 0f
  val edge = max(0f, bound) + max(0f, limit)
  return value.coerceIn(-edge, edge)
}

/**
 * 越界打阻尼,且**有渐近上限**:`over → ∞` 时结果趋近 `bound + limit`,永远越不过去。
 *
 * 小幅越界处的斜率就是 [EDGE_RESISTANCE](0.55),所以手感与原来的线性阻尼一致;
 * 差别只在拖得远的时候不再线性发散。
 */
fun rubberBand(value: Float, bound: Float, limit: Float): Float {
  if (value.isNaN()) return 0f
  val edge = max(0f, bound)
  val cap = max(0f, limit)
  // 封顶只为了让 ±Infinity 也走得通渐近式(否则 inf/inf = NaN 会把图弹没)
  val over = (abs(value) - edge).coerceAtMost(FINITE_OVERSHOOT_CEILING)
  if (over <= 0f) return value
  if (cap <= 0f) return sign(value) * edge
  val damped = cap * over * EDGE_RESISTANCE / (cap + over * EDGE_RESISTANCE)
  return sign(value) * (edge + damped)
}

/**
 * 以 [focal] 为锚做一次缩放后的新位移。
 *
 * `growth = 目标缩放 / 当前缩放`。焦点在中心系里的坐标固定不动 ⇒
 * `new = focal - (focal - old) * growth`。双击与捏合共用这一条:
 * 双击的锚是点按点,捏合的锚是两指中点。
 */
fun focalZoomPan(current: Float, focal: Float, growth: Float): Float {
  if (!growth.isFinite() || growth <= 0f) return current
  return focal - (focal - current) * growth
}

/**
 * 手势一帧之后的新**累计**位移:先按焦点重算(缩放),再叠平移,最后钳进当前缩放的边界。
 *
 * 「最后钳」这一步是缩小时不留黑边的关键:缩小让 [panBounds] 变小,
 * 上一帧合法的位移这一帧就越界了,不当场重钳的话缩回适配大小后会残留一大截位移。
 */
fun nextRawPan(
  current: Float,
  focal: Float,
  growth: Float,
  pan: Float,
  bound: Float,
  limit: Float,
): Float = clampRawPan(focalZoomPan(current, focal, growth) + pan, bound, limit)

/** 位移是否已经在适配位(双击该不该「复位」看它,见 `ZoomState.atFit`)。 */
fun atFitOffset(x: Float, y: Float): Boolean =
  abs(x) <= FIT_OFFSET_EPSILON && abs(y) <= FIT_OFFSET_EPSILON
