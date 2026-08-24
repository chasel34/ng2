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
  fun `还剩不到两屏半就该拉下一页`() {
    // 还剩 17 行 = 5,610px < 2.5 × 2330 = 5,825px
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
    // 还剩 40 行 = 13,200px,离两屏半还远
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
  fun `空列表和未量出来的列表不拉页`() {
    assertFalse(shouldLoadNextPage(-1, 0, rowHeight, viewport))
    assertFalse(shouldLoadNextPage(-1, 100, rowHeight, viewport))
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
