package com.chasel.ng2n.ui.common

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PagedListTest {

  private val viewport = 2330
  private val rowHeight = 330

  @Test
  fun `还剩不到预取屏数就该拉下一页`() {
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
    val runwayPx = (100 - 1 - newTrigger) * rowHeight
    assertTrue(runwayPx >= 2900 * 1.5, "余量 ${runwayPx}px 不够覆盖 224ms 往返的 1.5 倍")
  }

  @Test
  fun `量不出行高时退回项数口径`() {
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
    assertFalse(
      shouldLoadNextPage(
        lastVisibleIndex = 7,
        totalItemsCount = 20,
        averageItemSize = rowHeight,
        viewportSize = viewport,
        firstVisibleIndex = 0,
      ),
    )
    assertTrue(
      shouldLoadNextPage(
        lastVisibleIndex = 7,
        totalItemsCount = 20,
        averageItemSize = rowHeight,
        viewportSize = viewport,
        firstVisibleIndex = 1,
      ),
    )
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

  private val flingDistances = listOf(8868, 9212, 9760, 9616, 17692)

  private val flingSpeed = 15_000f

  private val slowestRoundTripMs = 224

  @Test
  fun `二轮的两屏半跑道比一把 fling 还短`() {
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
    assertTrue(
      FLING_CONTENT_WAIT_MS < 100,
      "FLING_CONTENT_WAIT_MS=${FLING_CONTENT_WAIT_MS}ms 已经越过验收的 100ms 无新内容闸",
    )
  }

  @Test
  fun `骨架行按屏数铺`() {
    assertEquals(15, tailPlaceholderCount(rowHeight, viewport))
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
    assertEquals(MAX_PLACEHOLDER_ROWS, tailPlaceholderCount(averageItemSize = 8, viewportSize = viewport))
    assertEquals(MIN_PLACEHOLDER_ROWS, tailPlaceholderCount(averageItemSize = viewport * 2, viewportSize = viewport))
  }

  @Test
  fun `不在加载中就不铺骨架`() {
    assertEquals(0, TailPlaceholders.None.count)
  }

  @Test
  fun `跑完了就交还 0`() {
    assertEquals(FlingHandoff.FINISHED, flingHandoff(0f, canScrollForward = false, moreContentComing = true, resumes = 0))
    assertEquals(FlingHandoff.FINISHED, flingHandoff(0.9f, canScrollForward = false, moreContentComing = true, resumes = 0))
  }

  @Test
  fun `真到底时余速原样交还给 overscroll`() {
    assertEquals(
      FlingHandoff.YIELD,
      flingHandoff(-4_000f, canScrollForward = false, moreContentComing = false, resumes = 0),
    )
  }

  @Test
  fun `顶边到头也原样交还`() {
    assertEquals(
      FlingHandoff.YIELD,
      flingHandoff(5_000f, canScrollForward = true, moreContentComing = true, resumes = 0),
    )
  }

  @Test
  fun `内容临时见底时扣住余速`() {
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
    assertEquals(
      FlingHandoff.FINISHED,
      flingHandoff(-9_000f, canScrollForward = false, moreContentComing = true, resumes = MAX_FLING_RESUMES),
    )
  }

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
