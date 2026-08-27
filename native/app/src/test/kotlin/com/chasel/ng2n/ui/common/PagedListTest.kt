package com.chasel.ng2n.ui.common

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 票 57 二轮:分页列表的两条判据。
 *
 * 富 trace(`t57-rich-topic.pb`)把「主题列表中段出 overscroll」的因果钉成一条:
 * fling 跑到**已加载内容**的末尾 → `LazyListState.onScroll` 在 `!canScrollForward`
 * 时返回 0 → `DefaultFlingBehavior` 看到 `abs(delta - consumed) > 0.5f` 就
 * `cancelAnimation()` 并把余速当返回值交出去 →
 * `AndroidEdgeEffectOverscrollEffect.applyToFling` 一发 `onAbsorbCompat()` 把它灌进
 * EdgeEffect → 之后约 430ms 只有重绘、没有重组/重测(trace 里 `Recomposer:animation`
 * / `Compose:recompose` / measure 全部为 0,而 RenderThread 每一帧都在画
 * `drawLayer [AndroidEdgeEffectOverscrollEffect]`,FrameTimeline 仍是 23 帧 on-time)。
 *
 * 能在 JVM 上钉死的是两件事:**什么时候该提前拉页**([shouldLoadNextPage]),
 * 以及**跑干时余速交给谁**([flingHandoff])。
 */
class PagedListTest {

  // 真机口径:视口 2330px、行高约 330px、一页约 28 行
  private val viewport = 2330
  private val rowHeight = 330

  // ---------------------------------------------------------------------------
  // 拉页判据
  // ---------------------------------------------------------------------------

  @Test
  fun `还剩不到预取屏数就该拉下一页`() {
    // 还剩 17 行 = 5,610px < 4 × 2330 = 9,320px
    assertTrue(
      shouldLoadNextPage(
        lastVisibleIndex = 100 - 1 - 17,
        totalItemsCount = 100,
        averageItemSize = rowHeight,
        viewportSize = viewport,
      ),
    )
  }

  @Test
  fun `还剩得多就先不拉`() {
    // 还剩 40 行 = 13,200px,离 4 屏(9,320px)还远
    assertFalse(
      shouldLoadNextPage(
        lastVisibleIndex = 100 - 1 - 40,
        totalItemsCount = 100,
        averageItemSize = rowHeight,
        viewportSize = viewport,
      ),
    )
  }

  @Test
  fun `新口径必须比旧的六项口径早得多`() {
    // 票 57 现场:13k px/s 下旧口径(还剩 6 项 ≈ 2,000px)只留约 155ms,
    // 而 trace 里请求往返实测 124–224ms —— 是个抛硬币,4/5 次抛输了
    val oldTrigger = 100 - 1 - MIN_ITEMS_AHEAD
    val newTrigger = (0 until 100).first { last ->
      shouldLoadNextPage(
        lastVisibleIndex = last,
        totalItemsCount = 100,
        averageItemSize = rowHeight,
        viewportSize = viewport,
      )
    }
    assertTrue(newTrigger < oldTrigger, "新判据应更早触发,实测 new=$newTrigger old=$oldTrigger")
    // 余量至少要覆盖 trace 里最慢那一次往返(224ms @ 13k px/s ≈ 2,900px)的 1.5 倍
    val runwayPx = (100 - 1 - newTrigger) * rowHeight
    assertTrue(runwayPx >= 2900 * 1.5, "余量 ${runwayPx}px 不够覆盖 224ms 往返的 1.5 倍")
  }

  @Test
  fun `量不出行高时退回项数口径`() {
    // 首帧:layoutInfo 还没有可用尺寸
    assertTrue(
      shouldLoadNextPage(
        lastVisibleIndex = 100 - 1 - MIN_ITEMS_AHEAD,
        totalItemsCount = 100,
        averageItemSize = 0,
        viewportSize = 0,
      ),
    )
    assertFalse(
      shouldLoadNextPage(
        lastVisibleIndex = 10,
        totalItemsCount = 100,
        averageItemSize = 0,
        viewportSize = 0,
      ),
    )
  }

  @Test
  fun `列表一动没动过就不预取`() {
    // 跑道加深到 4 屏之后「一页 ≈ 4.2 屏」只比阈值多一点点,进屏那一帧很容易
    // 顺手多打一发 thread.php(ADR-0002:能少打就少打)
    assertFalse(
      shouldLoadNextPage(
        lastVisibleIndex = 7,
        totalItemsCount = 20,
        averageItemSize = rowHeight,
        viewportSize = viewport,
        firstVisibleIndex = 0,
      ),
    )
    // 顶端那一行一滚出视口就恢复,后面全靠距离说话
    assertTrue(
      shouldLoadNextPage(
        lastVisibleIndex = 7,
        totalItemsCount = 20,
        averageItemSize = rowHeight,
        viewportSize = viewport,
        firstVisibleIndex = 1,
      ),
    )
    // 真到眼皮底下(只剩 MIN_ITEMS_AHEAD 项)时这条闸不拦 —— 那已经不是「一动没动」了
    assertTrue(
      shouldLoadNextPage(
        lastVisibleIndex = 100 - 1 - MIN_ITEMS_AHEAD,
        totalItemsCount = 100,
        averageItemSize = rowHeight,
        viewportSize = viewport,
        firstVisibleIndex = 0,
      ),
    )
  }

  @Test
  fun `空列表和未量出来的列表不拉页`() {
    assertFalse(shouldLoadNextPage(-1, 0, rowHeight, viewport))
    assertFalse(shouldLoadNextPage(-1, 100, rowHeight, viewport))
  }

  // ---------------------------------------------------------------------------
  // 三轮:跑道纵深(为什么 2.5 屏不够)
  // ---------------------------------------------------------------------------

  /** 二轮复验录屏量出来的一把 fling 走多远(`t57-topic-r2-phase.csv`,单位 px)。 */
  private val flingDistances = listOf(8868, 9212, 9760, 9616, 17692)

  /** 同一份样本里的快甩速度(px/s):15k 是中位,峰值更高。 */
  private val flingSpeed = 15_000f

  /** trace 里 `thread.php` 最慢的一次往返(ms)。 */
  private val slowestRoundTripMs = 224

  @Test
  fun `二轮的两屏半跑道比一把 fling 还短`() {
    // 这就是二轮为什么每一手都撞墙:跑道比一次手势能跑的距离还短,
    // 请求再准时也来不及 —— 阈值不是调小一点的问题,是量级不对
    val oldRunway = 2.5f * viewport
    assertTrue(
      flingDistances.all { it > oldRunway },
      "5/5 把 fling 都比 2.5 屏(${oldRunway.toInt()}px)跑得远:$flingDistances",
    )
  }

  @Test
  fun `预取跑道加骨架跑道要盖住最慢的一次往返`() {
    val prefetchPx = PREFETCH_SCREENS * viewport
    val placeholderPx = tailPlaceholderCount(rowHeight, viewport) * rowHeight
    val needPx = flingSpeed * slowestRoundTripMs / 1000f
    val budgetMs = (prefetchPx + placeholderPx) / flingSpeed * 1000f
    assertTrue(
      prefetchPx + placeholderPx >= needPx * 3,
      "总跑道 ${(prefetchPx + placeholderPx).toInt()}px(${budgetMs.toInt()}ms)" +
        "对 ${slowestRoundTripMs}ms 往返(${needPx.toInt()}px)没有 3 倍余量",
    )
  }

  @Test
  fun `HOLD 兜底自己不许越过 100ms 闸`() {
    // 二轮 250ms 的 HOLD 期间列表**完全静止**,它自己就是一次超标的静止窗。
    // 现在骨架行顶在前面,HOLD 退成兜底 —— 兜底不许变成缺陷
    assertTrue(
      FLING_CONTENT_WAIT_MS < 100,
      "FLING_CONTENT_WAIT_MS=${FLING_CONTENT_WAIT_MS}ms 已经越过验收的 100ms 无新内容闸",
    )
  }

  // ---------------------------------------------------------------------------
  // 三轮:尾部骨架行
  // ---------------------------------------------------------------------------

  @Test
  fun `骨架行按屏数铺`() {
    // 2 屏 = 4,660px / 330px = 14.1 → 15 行
    assertEquals(15, tailPlaceholderCount(rowHeight, viewport))
    // 铺出来的跑道要够 15k px/s 下的一次往返
    val runwayMs = 15 * rowHeight / flingSpeed * 1000f
    assertTrue(runwayMs >= slowestRoundTripMs, "骨架跑道只有 ${runwayMs.toInt()}ms")
  }

  @Test
  fun `量不出行高时给兜底张数`() {
    assertEquals(DEFAULT_PLACEHOLDER_ROWS, tailPlaceholderCount(0, viewport))
    assertEquals(DEFAULT_PLACEHOLDER_ROWS, tailPlaceholderCount(rowHeight, 0))
  }

  @Test
  fun `骨架行张数有上下限`() {
    // 极矮的行(比如纯文字一行的搜索结果)不许把上千张骨架塞进列表
    assertEquals(MAX_PLACEHOLDER_ROWS, tailPlaceholderCount(averageItemSize = 8, viewportSize = viewport))
    // 极高的行(带大图的行)也得有几张顶着
    assertEquals(MIN_PLACEHOLDER_ROWS, tailPlaceholderCount(averageItemSize = viewport * 2, viewportSize = viewport))
  }

  @Test
  fun `不在加载中就不铺骨架`() {
    assertEquals(0, TailPlaceholders.None.count)
  }

  // ---------------------------------------------------------------------------
  // fling 的消费约定
  // ---------------------------------------------------------------------------

  @Test
  fun `跑完了就交还 0`() {
    assertEquals(FlingHandoff.FINISHED, flingHandoff(0f, canScrollForward = false, moreContentComing = true, resumes = 0))
    // 与 DefaultFlingBehavior 自己的 `abs(v) > 1f` 同口径
    assertEquals(FlingHandoff.FINISHED, flingHandoff(0.9f, canScrollForward = false, moreContentComing = true, resumes = 0))
  }

  @Test
  fun `真到底时余速原样交还给 overscroll`() {
    // 没有下一页了 = 真的列表边缘,EdgeEffect 该出就出,手感一点不改
    assertEquals(
      FlingHandoff.YIELD,
      flingHandoff(-4_000f, canScrollForward = false, moreContentComing = false, resumes = 0),
    )
  }

  @Test
  fun `顶边到头也原样交还`() {
    // 往回甩到顶:前向还能滚,不属于「内容见底」,不许吞
    assertEquals(
      FlingHandoff.YIELD,
      flingHandoff(5_000f, canScrollForward = true, moreContentComing = true, resumes = 0),
    )
  }

  @Test
  fun `内容临时见底时扣住余速`() {
    // 这就是票 57 主题列表那四处塌陷的现场:还有下一页在路上,前向暂时滚不动
    assertEquals(
      FlingHandoff.HOLD,
      flingHandoff(-9_000f, canScrollForward = false, moreContentComing = true, resumes = 0),
    )
  }

  @Test
  fun `接力有上限`() {
    assertEquals(
      FlingHandoff.HOLD,
      flingHandoff(-9_000f, canScrollForward = false, moreContentComing = true, resumes = MAX_FLING_RESUMES - 1),
    )
    // 一页只来一屏时不许无限接力,更不许把余速再灌进 EdgeEffect
    assertEquals(
      FlingHandoff.FINISHED,
      flingHandoff(-9_000f, canScrollForward = false, moreContentComing = true, resumes = MAX_FLING_RESUMES),
    )
  }

  // ---------------------------------------------------------------------------
  // 接线:PagedFlingBehavior 有没有照判据办事
  // ---------------------------------------------------------------------------

  private class ScriptedFling(private val leftovers: List<Float>) : FlingBehavior {
    val seen = mutableListOf<Float>()
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
      seen += initialVelocity
      return leftovers.getOrElse(seen.size - 1) { 0f }
    }
  }

  private object NoopScrollScope : ScrollScope {
    override fun scrollBy(pixels: Float): Float = pixels
  }

  private suspend fun runFling(behavior: PagedFlingBehavior, velocity: Float): Float =
    with(behavior) { with(NoopScrollScope) { performFling(velocity) } }

  @Test
  fun `fling 自己跑完就一次调用了事`() = runTest {
    val delegate = ScriptedFling(listOf(0f))
    val behavior = PagedFlingBehavior(
      delegate = delegate,
      moreContentComing = { true },
      canScrollForward = { true },
    )
    assertEquals(0f, runFling(behavior, -12_000f))
    assertEquals(listOf(-12_000f), delegate.seen)
  }

  @Test
  fun `真边缘的余速一路传到底`() = runTest {
    val delegate = ScriptedFling(listOf(-4_000f))
    val behavior = PagedFlingBehavior(
      delegate = delegate,
      moreContentComing = { false },
      canScrollForward = { false },
    )
    assertEquals(-4_000f, runFling(behavior, -12_000f))
    assertEquals(1, delegate.seen.size, "真到底不该重跑 fling")
  }
}
