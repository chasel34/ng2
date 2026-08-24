package com.chasel.ng2n.ui.common

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无限滚动列表的两件配件:**什么时候拉下一页**、**内容临时见底时 fling 怎么办**。
 *
 * ## 票 57 二轮:富 trace 把「中段 overscroll」钉死在这里
 *
 * 一轮把 `PullToRefreshBox` 的 nested-scroll 节点当成主嫌;二轮拿
 * `t57-rich-topic.pb` 逐帧对完之后可以给它摘帽:fling 期间 `Scrollable` 派发的
 * `NestedScrollSource` 是 `SideEffect` 而不是 `UserInput`,material3 的
 * `onPreScroll`/`onPostScroll` 两条分支都直接 `Offset.Zero` 放行;`onPreFling` 在
 * `distancePulled == 0f` 时也返回 0。**下拉刷新不在快甩这条路径上。**
 *
 * 真正的因果是这一条(trace 相对时刻,主题列表首处塌陷):
 *
 * | 时刻 | 事件 |
 * |---:|---|
 * | 3,786.2ms | 一帧 13 次重组 + `Compose:sideeffects`:`shouldLoadMore` 点亮、发下一页请求 |
 * | 3,919.1ms | 同一帧里先跑 `animation`(fling 的 `scrollBy`),**再** apply 新一页 |
 * | 3,919.x | fling 那一下 `scrollBy` 撞上「已加载内容的末尾」,`canScrollForward` 为假 |
 * | 3,923.7ms | RenderThread 开始画 `drawLayer [AndroidEdgeEffectOverscrollEffect]`,连画 52 帧 |
 * | 4,121.9ms | 下一次 ACTION_DOWN,列表恢复 19.88k px/s |
 *
 * 中间那一步是 Compose 的既定约定,一环扣一环:
 *
 * 1. `LazyListState.onScroll` 在 `!canScrollForward` 时直接 `return 0f`;
 * 2. `DefaultFlingBehavior` 看到 `abs(delta - consumed) > 0.5f` 就 `cancelAnimation()`,
 *    并把**没跑完的速度**当返回值交出去 —— 这就是富 trace 里
 *    「`Recomposer:animation` / `Compose:recompose` / measure 全部归零」的原因:
 *    fling animator 真的死了;
 * 3. `ScrollingLogic.onScrollStopped` 把它算进 `leftForOverscroll`,
 *    `AndroidEdgeEffectOverscrollEffect.applyToFling` 一发 `onAbsorbCompat()`
 *    把这份速度**灌进 EdgeEffect**;
 * 4. stretch 的回弹动画只改 `redrawSignal`(只触发重绘、不触发重组/重测),
 *    于是接下来约 430ms:UI traversal + RT 照常约 120Hz 出帧、FrameTimeline 全部 on-time、
 *    janky 0 —— 但列表一动不动,录屏相位只剩 0 / 约 0.5k px/s 的亚像素蠕动。
 *
 * 一句话:**这不是「到底了」,是「暂时没内容可滚了」**。四次塌陷分别落在
 * 3,923 / 4,918 / 5,808 / 6,722ms —— 正好是第 4、6、8、10 手,每两手一页,
 * 与「一页约 26–30 行、一手约吃 13 行」完全对得上。
 *
 * 所以二轮改两处:
 *
 * - [shouldLoadNextPage]:判据从「还剩 6 项」换成**按距离**的「还剩不到
 *   [PREFETCH_SCREENS] 屏」。旧口径 6 行约 2,000px,在 13k px/s 下只有约 155ms 余量,
 *   而 trace 里请求往返实测 124–224ms —— 是个抛硬币。新口径给出约 5,800px / 约 390ms,
 *   对同一批往返有 1.7–3 倍余量。
 * - [PagedFlingBehavior]:万一还是跑干了(网络更慢),**余速不许灌进 EdgeEffect**,
 *   而是原地扣住、等新一页量出来再把这一把 fling 接着跑完。真正到底(没有下一页了)
 *   时原样交还余速 —— 边缘该有的 overscroll 一点不少。
 */

/** 还剩不到这么多屏没滚,就该把下一页拉起来了。 */
const val PREFETCH_SCREENS: Float = 2.5f

/** 屏高/行高都还没量出来时的兜底口径(= 一轮之前的老判据)。 */
const val MIN_ITEMS_AHEAD: Int = 6

/** 内容见底后最多扣住这一把 fling 多久等下一页;超时就当真到底,老实停下。 */
const val FLING_CONTENT_WAIT_MS: Long = 250L

/** 同一把 fling 最多续几次,防止「一页只来一屏」时无限接力。 */
const val MAX_FLING_RESUMES: Int = 3

/** 小于这个余速就算跑完了(与 `DefaultFlingBehavior` 自己的 `abs(v) > 1f` 同口径)。 */
private const val SETTLED_VELOCITY = 1f

/**
 * 该拉下一页了吗。
 *
 * [averageItemSize] / [viewportSize] 任一没量出来(0)就退回项数口径,只按
 * [MIN_ITEMS_AHEAD] 判 —— 首帧、空列表都走这一条。
 */
fun shouldLoadNextPage(
  lastVisibleIndex: Int,
  totalItemsCount: Int,
  averageItemSize: Int,
  viewportSize: Int,
  screensAhead: Float = PREFETCH_SCREENS,
): Boolean {
  if (totalItemsCount <= 0 || lastVisibleIndex < 0) return false
  val itemsAhead = totalItemsCount - 1 - lastVisibleIndex
  if (itemsAhead <= MIN_ITEMS_AHEAD) return true
  if (averageItemSize <= 0 || viewportSize <= 0) return false
  return itemsAhead.toFloat() * averageItemSize < viewportSize * screensAhead
}

/** 可见项的平均高度(px);量不出来给 0。 */
fun LazyListLayoutInfo.averageVisibleItemSize(): Int {
  val items = visibleItemsInfo
  if (items.isEmpty()) return 0
  val first = items.first()
  val last = items.last()
  val span = last.offset + last.size - first.offset
  return if (span > 0) span / items.size else 0
}

/** 视口高度(px)。 */
fun LazyListLayoutInfo.viewportSize(): Int = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)

/**
 * 「该拉下一页了」的观察点。[itemCount] 只用来在数据长出来时重建 `derivedStateOf`,
 * 与各屏原来的 `remember(listState, rows.size)` 同义。
 */
@Composable
fun rememberShouldLoadNextPage(listState: LazyListState, itemCount: Int): State<Boolean> =
  remember(listState, itemCount) {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
      shouldLoadNextPage(
        lastVisibleIndex = last.index,
        totalItemsCount = info.totalItemsCount,
        averageItemSize = info.averageVisibleItemSize(),
        viewportSize = info.viewportSize(),
      )
    }
  }

/**
 * 分页列表专用的 fling:内容**临时**见底时不把余速丢给 overscroll。
 *
 * @param moreContentComing 还有下一页在路上吗(`hasNextPage || loadingNextPage`)。
 *   为假就是真到底,余速原样交还 —— 该有的边缘 overscroll 照旧。
 */
@Composable
fun rememberPagedFlingBehavior(
  listState: LazyListState,
  moreContentComing: () -> Boolean,
): FlingBehavior {
  val delegate = ScrollableDefaults.flingBehavior()
  val pending = rememberUpdatedState(moreContentComing)
  return remember(delegate, listState) {
    PagedFlingBehavior(
      delegate = delegate,
      moreContentComing = { pending.value() },
      canScrollForward = { listState.canScrollForward },
    )
  }
}

/** 一段 fling 跑完之后,余速该怎么处置。 */
enum class FlingHandoff {
  /** 正常跑完(或余速已可忽略):交还 0,谁都不用管。 */
  FINISHED,

  /** 原样交还余速:让 `applyToFling` 灌进 EdgeEffect —— **真**边缘就该这样。 */
  YIELD,

  /** 扣住余速等下一页:内容只是**临时**见底,中段不许出 overscroll。 */
  HOLD,
}

/**
 * 消费约定,票 57 二轮的核心一条。
 *
 * @param canScrollForward 这一刻列表**前向**还能不能滚。为真说明这一下不是
 *   「前向内容见底」(往回甩到顶就属于这一类),原样放行。
 * @param moreContentComing 还有下一页在路上吗。为假就是真到底。
 * @param resumes 这一把 fling 已经接力过几次。
 */
fun flingHandoff(
  velocityLeft: Float,
  canScrollForward: Boolean,
  moreContentComing: Boolean,
  resumes: Int,
): FlingHandoff = when {
  abs(velocityLeft) <= SETTLED_VELOCITY -> FlingHandoff.FINISHED
  canScrollForward -> FlingHandoff.YIELD
  !moreContentComing -> FlingHandoff.YIELD
  resumes >= MAX_FLING_RESUMES -> FlingHandoff.FINISHED
  else -> FlingHandoff.HOLD
}

internal class PagedFlingBehavior(
  private val delegate: FlingBehavior,
  private val moreContentComing: () -> Boolean,
  private val canScrollForward: () -> Boolean,
) : FlingBehavior {

  override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
    val scope = this
    var velocity = initialVelocity
    var resumes = 0
    while (true) {
      val left = with(delegate) { scope.performFling(velocity) }
      when (flingHandoff(left, canScrollForward(), moreContentComing(), resumes)) {
        FlingHandoff.FINISHED -> return 0f
        FlingHandoff.YIELD -> return left
        FlingHandoff.HOLD -> Unit
      }
      resumes++
      // 扣住余速等下一页量出来。等待期间手指再按下会以 MutatePriority.UserInput
      // 取消整条协程(标准交接),这里不需要额外处理
      val arrived = withTimeoutOrNull(FLING_CONTENT_WAIT_MS) {
        snapshotFlow { canScrollForward() }.first { it }
      } != null
      // 没等到就老实停下;**但仍然返回 0**:中段的 EdgeEffect 是这张票的病灶,
      // 宁可少一次视觉反馈,也不要「有帧、120Hz、内容不动」的 430ms
      if (!arrived) return 0f
      velocity = left
    }
  }
}
