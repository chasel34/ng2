package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.ceil
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
 *   [PREFETCH_SCREENS] 屏」。
 * - [PagedFlingBehavior]:万一还是跑干了(网络更慢),**余速不许灌进 EdgeEffect**,
 *   而是原地扣住、等新一页量出来再把这一把 fling 接着跑完。真正到底(没有下一页了)
 *   时原样交还余速 —— 边缘该有的 overscroll 一点不少。
 *
 * ## 票 57 三轮:二轮只是把「蠕动」换成了「明写的静止」
 *
 * 二轮合并后的真机复验(`acceptance/perf/t57-topic-r2-phase.csv`)逐帧摊开是这样的:
 *
 * | 撞墙时刻 | 撞墙前 4 帧的速度(px/s) | 静止时长 | 恢复速度 | 谁把它拉起来 |
 * |---:|---|---:|---:|---|
 * | 1.594s | 15,000 → 3,544 → 1,959 → 850 | 333ms | 14,470 | 下一次 ACTION_DOWN |
 * | 2.609s | 14,324 → 5,049 → 1,388 → 495 | 233ms | 16,650 | 下一次 ACTION_DOWN |
 * | 3.582s | 15,407 → 1,467 → 984 → 480 | 291ms | 21,235 | 下一次 ACTION_DOWN |
 * | 4.597s | 17,637 → 4,629 → 1,295 → 497 | 291ms | 19,817 | 下一次 ACTION_DOWN |
 *
 * 三条结论,一条一条钉:
 *
 * 1. **「4 帧从满速到 0」不是衰减曲线,是一堵墙** —— 而且墙后面接的是一段
 *    `dy` 恰好为 0 的静止(只剩 ±4px 的 stretch 回弹噪声)。这就是 [PagedFlingBehavior]
 *    的 HOLD:二轮把 EdgeEffect 的亚像素蠕动换成了**显式的冻结**,观感一模一样,
 *    而且 `FLING_CONTENT_WAIT_MS = 250ms` 本身就大于验收的 100ms 闸 ——
 *    只要 HOLD 触发,这张票就不可能过。
 * 2. **接力几乎从没发生**:4/4 个边界都是静止到下一次按下才动,即
 *    `withTimeoutOrNull` 全部超时、`performFling` 返回 0。票面那处「速度只剩前段
 *    6.5%」是**冲进冻结的那 4 帧斜坡**,不是接力后的续跑。
 * 3. **距离预取没接错线,是纵深不够**。同一份 CSV 量出来:一把
 *    `input swipe … 100`(约 15k px/s)要跑 **8,868 / 9,212 / 9,760 / 9,616px**,
 *    最后那把没被打断的自然衰减跑了 **17,692px**;全程 55,132px 吃掉约 5 页,
 *    即**一页约 11,000px(35 行 × 约 315px),一把 fling 正好吃掉一整页**。
 *    而 `TopicListRepository.loadNextPage` 是**严格串行**的(`loadingNextPage`
 *    去重 + 每 key 一把 `Mutex`,同时最多一发 `thread.php` 在飞),
 *    于是稳态就是「一手一页」:2.5 屏 ≈ 6,000px ≈ 400ms 的余量要独自盖住
 *    请求往返 + 解析 + 合页 + 组合 35 行,输了 4/4。
 *
 * 所以三轮不再去调那个阈值,而是**把 fling 和网络解耦**:
 *
 * - [tailPlaceholders]:下一页在路上时,在列表尾部铺 [PLACEHOLDER_SCREENS] 屏
 *   **能滚的**骨架行。`canScrollForward` 于是一直为真,fling 有真实像素可推进 ——
 *   墙没了,HOLD 也就不触发了。新一页落地时骨架被同索引位置的真行替换,
 *   `LazyListState` 按「首个可见项的 index + offset」锚定,视口里那一格不动。
 * - [PREFETCH_SCREENS] 2.5 → 4 屏:一把 fling 就能跑 3.7–7.4 屏,2.5 屏的跑道
 *   比单把 fling 还短。4 屏 ≈ 9,600px ≈ 640ms,叠上骨架的 2 屏 ≈ 320ms,
 *   总预算约 960ms —— 对实测 124–224ms 的往返有 4 倍以上余量。
 *   请求**总数不变**(还是一页一发),只是每发都提前。
 * - [FLING_CONTENT_WAIT_MS] 250 → 80ms:HOLD 退成纯兜底。它是这张票的病灶之一,
 *   所以它自己**必须短于 100ms 闸**:哪怕真触发了,也构不成一次超标的静止窗。
 */

/**
 * 还剩不到这么多屏没滚,就该把下一页拉起来了。
 *
 * 三轮从 2.5 抬到 4:实测一把快甩要跑 8,868–17,692px = 3.7–7.4 屏,
 * 2.5 屏的跑道比一把 fling 还短(见文件头的表)。
 */
const val PREFETCH_SCREENS: Float = 4f

/** 屏高/行高都还没量出来时的兜底口径(= 一轮之前的老判据)。 */
const val MIN_ITEMS_AHEAD: Int = 6

/**
 * 内容见底后最多扣住这一把 fling 多久等下一页;超时就当真到底,老实停下。
 *
 * 三轮从 250 砍到 80:HOLD 期间列表是**完全静止**的,250ms 本身就越过了
 * 验收的「>100ms 无新内容」闸。现在有 [tailPlaceholders] 顶在前面,HOLD 只是兜底,
 * 兜底不许自己变成缺陷。
 */
const val FLING_CONTENT_WAIT_MS: Long = 80L

/** 同一把 fling 最多续几次,防止「一页只来一屏」时无限接力。 */
const val MAX_FLING_RESUMES: Int = 3

/** 小于这个余速就算跑完了(与 `DefaultFlingBehavior` 自己的 `abs(v) > 1f` 同口径)。 */
private const val SETTLED_VELOCITY = 1f

/**
 * 该拉下一页了吗。
 *
 * [averageItemSize] / [viewportSize] 任一没量出来(0)就退回项数口径,只按
 * [MIN_ITEMS_AHEAD] 判 —— 首帧、空列表都走这一条。
 *
 * [firstVisibleIndex] 是三轮加的**一条闸**:跑道从 2.5 屏加深到 4 屏之后,
 * 「一页 35 行 ≈ 4.2 屏」只比阈值多一点点 —— 屏幕再高一点、或者这一页被屏蔽规则
 * 削掉几行,进屏那一帧就会顺手多打一发 `thread.php`。ADR-0002 的口径是能少打就少打:
 * **列表还一动没动过就不预取**。手指一动(顶端那一行滚出视口)判据立刻恢复,
 * 后面全靠距离说话。
 */
fun shouldLoadNextPage(
  lastVisibleIndex: Int,
  totalItemsCount: Int,
  averageItemSize: Int,
  viewportSize: Int,
  firstVisibleIndex: Int = 1,
  screensAhead: Float = PREFETCH_SCREENS,
): Boolean {
  if (totalItemsCount <= 0 || lastVisibleIndex < 0) return false
  val itemsAhead = totalItemsCount - 1 - lastVisibleIndex
  if (itemsAhead <= MIN_ITEMS_AHEAD) return true
  if (averageItemSize <= 0 || viewportSize <= 0) return false
  if (firstVisibleIndex <= 0) return false
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
        firstVisibleIndex = info.visibleItemsInfo.first().index,
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

// ---------------------------------------------------------------- 尾部占位行(票 57 三轮)

/** 下一页在路上时,尾部铺几屏能滚的骨架行。 */
const val PLACEHOLDER_SCREENS: Float = 2f

/** 量不出行高/视口时的兜底张数。 */
const val DEFAULT_PLACEHOLDER_ROWS: Int = 12

/** 骨架行张数的下限:再快的往返也得有几张顶着。 */
const val MIN_PLACEHOLDER_ROWS: Int = 6

/** 上限:骨架行是「借来的跑道」,不该在慢速滚动时糊满一屏还不止。 */
const val MAX_PLACEHOLDER_ROWS: Int = 24

/** 骨架行量不出真实行高时的兜底高度(≈ 单行标题的主题行)。 */
val DEFAULT_PLACEHOLDER_HEIGHT: Dp = 76.dp

/**
 * 铺几张骨架行才够 [screens] 屏。
 *
 * 真机口径:视口约 2,400px、行约 315px → 16 张 ≈ 5,000px ≈ 15k px/s 下的 330ms,
 * 覆盖实测 124–224ms 的 `thread.php` 往返还有富余。
 */
fun tailPlaceholderCount(
  averageItemSize: Int,
  viewportSize: Int,
  screens: Float = PLACEHOLDER_SCREENS,
): Int {
  if (averageItemSize <= 0 || viewportSize <= 0) return DEFAULT_PLACEHOLDER_ROWS
  val rows = ceil(viewportSize * screens / averageItemSize).toInt()
  return rows.coerceIn(MIN_PLACEHOLDER_ROWS, MAX_PLACEHOLDER_ROWS)
}

/** [rememberTailPlaceholders] 的产物:铺几张、每张多高。 */
@Immutable
data class TailPlaceholders(val count: Int, val rowHeight: Dp) {
  companion object {
    val None = TailPlaceholders(count = 0, rowHeight = DEFAULT_PLACEHOLDER_HEIGHT)
  }
}

/**
 * 尾部骨架行的尺寸,**只在「开始拉下一页」那一刻量一次**。
 *
 * 两条都是故意的:
 *
 * - 用 `Snapshot.withoutReadObservation` 读 `layoutInfo` —— 不然组合会订阅它,
 *   滚动中每帧重组一次整屏列表,治病治出新病;
 * - `remember(listState, loading)` 让张数在这一次加载期间**恒定** —— 张数跟着
 *   `layoutInfo` 抖动等于每帧增删列表项,LazyList 的回收池会被搅烂。
 */
@Composable
fun rememberTailPlaceholders(listState: LazyListState, loading: Boolean): TailPlaceholders {
  val density = LocalDensity.current
  return remember(listState, loading, density) {
    if (!loading) return@remember TailPlaceholders.None
    Snapshot.withoutReadObservation {
      val info = listState.layoutInfo
      val average = info.averageVisibleItemSize()
      TailPlaceholders(
        count = tailPlaceholderCount(average, info.viewportSize()),
        rowHeight = if (average > 0) with(density) { average.toDp() } else DEFAULT_PLACEHOLDER_HEIGHT,
      )
    }
  }
}

/** 骨架行的 key 前缀:与主题行的 `tid`(Long)天然不同型,不会撞。 */
private const val PLACEHOLDER_KEY = "ng2n:paged-placeholder:"

/**
 * 尾部骨架行 —— 票 57 三轮的主改动。
 *
 * 它存在的**唯一理由**是让 `LazyListState.canScrollForward` 在「下一页还在路上」
 * 这段时间里保持为真。Compose 的既定链路是:`canScrollForward` 一假,
 * `DefaultFlingBehavior` 立刻 `cancelAnimation()` 把 fling 打死,余速要么灌进
 * EdgeEffect(一轮/二轮之前的现场),要么被 [PagedFlingBehavior] 扣住等待(二轮的现场)——
 * 两条路的观感都是「有帧、120Hz、内容不动」。给它几屏真的能滚的像素,这条链根本不会启动。
 *
 * 新一页落地时骨架整批消失、真行在**同一批索引**上长出来,`LazyListState` 按
 * 「首个可见项的 index + offset」锚定,视口里那一格不动 —— 换掉的都在视口之上。
 *
 * 骨架的画法沿用详情页 `PageSkeleton` 的语言(`colors.quote` 的圆角条),
 * 宽度按行号轮换:整片等宽条会让逐帧相位相关退化成「无位移」,验收口径自己会看走眼。
 */
fun LazyListScope.tailPlaceholders(placeholders: TailPlaceholders) {
  if (placeholders.count <= 0) return
  items(
    count = placeholders.count,
    key = { index -> PLACEHOLDER_KEY + index },
    contentType = { "paged-placeholder" },
  ) { index -> PagedRowPlaceholder(index = index, height = placeholders.rowHeight) }
}

/** 骨架行的标题条宽度轮换(设计稿没有这一屏,按 `PageSkeleton` 的三档延伸)。 */
private val PLACEHOLDER_TITLE_WIDTHS = listOf(0.92f, 0.68f, 0.84f, 0.55f, 0.76f)

@Composable
private fun PagedRowPlaceholder(index: Int, height: Dp) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .height(height)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
  ) {
    Box(
      Modifier
        .fillMaxWidth(PLACEHOLDER_TITLE_WIDTHS[index % PLACEHOLDER_TITLE_WIDTHS.size])
        .height(13.dp)
        .clip(RoundedCornerShape(6.5.dp))
        .background(colors.quote),
    )
    Spacer(Modifier.height(12.dp))
    Box(
      Modifier
        .fillMaxWidth(0.34f)
        .height(10.dp)
        .clip(RoundedCornerShape(5.dp))
        .background(colors.quote),
    )
  }
}
