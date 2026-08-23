package com.chasel.ng2n.ui.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手工移植自 `src/ui/paging.test.ts`(`swipe*` 一族不移植,理由见 `Paging.kt` 文件头)。 */
class PagingTest {

  @Test
  fun `clampPage 夹在 1 到 totalPages 之间`() {
    assertEquals(1, clampPage(0, 13))
    assertEquals(1, clampPage(-5, 13))
    assertEquals(7, clampPage(7, 13))
    assertEquals(13, clampPage(99, 13))
  }

  @Test
  fun `clampPage 页数为 0 或负时仍然有第 1 页`() {
    assertEquals(1, clampPage(1, 0))
    assertEquals(1, clampPage(3, -2))
  }

  @Test
  fun `visiblePages 页数少时全部铺出来`() {
    assertEquals(listOf(1, 2, 3, 4, 5), visiblePages(1, 5))
  }

  @Test
  fun `visiblePages 页数多时只画当前页附近的窗口 首尾两页固定露出`() {
    val pages = visiblePages(50, 200)
    assertEquals(1, pages.first())
    assertEquals(200, pages.last())
    assertTrue(50 in pages)
    assertTrue(46 in pages)
    assertTrue(54 in pages)
    assertTrue(45 !in pages)
  }

  @Test
  fun `visiblePages 永远升序且不重复`() {
    for (page in listOf(1, 2, 7, 199, 200)) {
      val pages = visiblePages(page, 200)
      assertEquals(pages.sorted(), pages)
      assertEquals(pages.toSet().size, pages.size)
    }
  }

  @Test
  fun `visiblePages 只有一页时就一格`() {
    assertEquals(listOf(1), visiblePages(1, 1))
  }

  @Test
  fun `parseJumpTarget 认合法页码`() {
    assertEquals(7, parseJumpTarget("7", 13))
    assertEquals(13, parseJumpTarget(" 13 ", 13))
  }

  @Test
  fun `parseJumpTarget 超范围返回 null —— 跳页不夹逼,要让用户知道输错了`() {
    assertNull(parseJumpTarget("0", 13))
    assertNull(parseJumpTarget("14", 13))
    assertNull(parseJumpTarget("-3", 13))
  }

  @Test
  fun `parseJumpTarget 不是整数一律拒`() {
    for (bad in listOf("", "abc", "3.5", "1e3x", " ")) {
      assertNull(parseJumpTarget(bad, 13), "「$bad」不该被当成页码")
    }
  }

  // --------------------------------------------------------- pagerPageCount(票 20)

  @Test
  fun `pagerPageCount 至少装得下当前页 —— 总页数还没回来也不许把页码钳掉`() {
    // 首帧:数据没回来,totalPages 还是进场页兜的底
    assertEquals(3, pagerPageCount(1, 3))
    assertEquals(3, pagerPageCount(3, 3))
    // 真实页数回来后由 totalPages 说了算
    assertEquals(47, pagerPageCount(47, 3))
  }

  @Test
  fun `pagerPageCount 永远不小于 1`() {
    assertEquals(1, pagerPageCount(0, 0))
    assertEquals(1, pagerPageCount(-2, -5))
    assertEquals(1, pagerPageCount(1, 1))
  }

  @Test
  fun `pagerPageCount 与 clampPage 合起来 首帧不会把带页码进场的页码打回第 1 页`() {
    // 票 20 的现场:pager 把 currentPage 钳进 [0, pageCount-1],
    // 再由 settledPage 回写给 ViewModel。pageCount 装得下,这一圈就是恒等
    val page = 3
    val totalPages = 3 // TopicViewModel 的初值 = 进场页
    val settled = (page - 1).coerceIn(0, pagerPageCount(totalPages, page) - 1)
    assertEquals(page, clampPage(settled + 1, totalPages))
  }
}
