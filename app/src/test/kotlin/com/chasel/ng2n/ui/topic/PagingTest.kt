package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.Floor
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
  fun `pagerPageCount 至少装得下当前页 —— 总页数还没回来也不许把页码钳掉`() {
    assertEquals(3, pagerPageCount(1, 3))
    assertEquals(3, pagerPageCount(3, 3))
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
    val page = 3
    val totalPages = 3
    val settled = (page - 1).coerceIn(0, pagerPageCount(totalPages, page) - 1)
    assertEquals(page, clampPage(settled + 1, totalPages))
  }

  private fun louOfPage(page: Int, rowsPerPage: Int = 20): List<Long> =
    ((page - 1) * rowsPerPage until page * rowsPerPage).map { it.toLong() }

  @Test
  fun `floorScrollIndex 目标楼在页中部 —— 楼层下标再加 header 那一格`() {
    assertEquals(15, floorScrollIndex(louOfPage(4), targetFloor = 74, page = 4, rowsPerPage = 20))
    assertEquals(7, floorScrollIndex(louOfPage(2), targetFloor = 26, page = 2, rowsPerPage = 20))
    assertEquals(8, floorScrollIndex(louOfPage(1), targetFloor = 7, page = 1, rowsPerPage = 20))
  }

  @Test
  fun `floorScrollIndex 页顶那一楼落在 header 后面第一格 —— 不是第 0 格`() {
    assertEquals(1, floorScrollIndex(louOfPage(3), targetFloor = 40, page = 3, rowsPerPage = 20))
    assertEquals(1, floorScrollIndex(louOfPage(1), targetFloor = 0, page = 1, rowsPerPage = 20))
  }

  @Test
  fun `floorScrollIndex 目标楼不在本页 —— 给 null,翻页途中不许拿旧页错滚`() {
    assertNull(floorScrollIndex(louOfPage(2), targetFloor = 74, page = 2, rowsPerPage = 20))
    assertNull(floorScrollIndex(louOfPage(4), targetFloor = 7, page = 4, rowsPerPage = 20))
    assertNull(floorScrollIndex(louOfPage(3), targetFloor = 200, page = 3, rowsPerPage = 20))
  }

  @Test
  fun `floorScrollIndex 有热门回复区也只占 header 那一格`() {
    val hot = pageModel(page = 1, lous = louOfPage(1), hotReplyLous = listOf(3L, 11L))
    val plain = pageModel(page = 1, lous = louOfPage(1))
    assertTrue(hot.hotReplies.isNotEmpty())
    assertTrue(plain.hotReplies.isEmpty())

    val withHot = floorScrollIndex(
      floorLous = hot.floors.map { it.lou },
      targetFloor = 7,
      page = hot.page,
      rowsPerPage = hot.rowsPerPage,
    )
    val withoutHot = floorScrollIndex(
      floorLous = plain.floors.map { it.lou },
      targetFloor = 7,
      page = plain.page,
      rowsPerPage = plain.rowsPerPage,
    )
    assertEquals(8, withHot, "7 楼是第 1 页的第 8 条,前面只有 header 一格")
    assertEquals(withHot, withoutHot, "热门回复区不额外占一行")
    assertEquals(1, TOPIC_LIST_HEADER_ROWS)
  }

  private fun pageModel(
    page: Int,
    lous: List<Long>,
    hotReplyLous: List<Long> = emptyList(),
    rowsPerPage: Int = 20,
  ): PageRenderModel {
    fun floor(lou: Long) = Floor(
      pid = 800000000 + lou,
      lou = lou,
      authorId = 1,
      authorKey = "1",
      content = "第 $lou 楼",
      postedAt = 1786075200,
      postedAtText = "2026-08-07 12:00",
    )
    val detail = TopicDetail(
      tid = 45150945,
      subject = "测试主题",
      page = page,
      totalRows = 400,
      rowsPerPage = rowsPerPage,
      totalPages = 20,
      attachBase = "https://img.nga.cn/attachments",
      floors = lous.map(::floor),
      hotReplies = hotReplyLous.map(::floor),
      users = mapOf(
        "1" to FloorUser(key = "1", uid = 1, name = "甲", rawName = "甲", reputation = 1.0),
      ),
    )
    return TopicPageBuilder.build(detail, 45150945, TopicFixtures.STYLE, TopicFixtures.URLS)
  }

  @Test
  fun `floorScrollIndex 相邻页预览没有 header —— headerRows 传 0 就是纯楼层下标`() {
    assertEquals(14, floorScrollIndex(louOfPage(4), 74, page = 4, rowsPerPage = 20, headerRows = 0))
  }

  @Test
  fun `floorScrollIndex 楼层被删有空洞 —— 落到它后面最近的一楼`() {
    val floors = louOfPage(4).filterNot { it == 62L || it == 63L }
    assertEquals(
      3,
      floorScrollIndex(floors, targetFloor = 62, page = 4, rowsPerPage = 20),
    )
  }

  @Test
  fun `floorScrollIndex 尾巴整段被删光 —— 落到本页最后一楼`() {
    val floors = louOfPage(4).filter { it <= 65L }
    assertEquals(
      6,
      floorScrollIndex(floors, targetFloor = 74, page = 4, rowsPerPage = 20),
    )
  }

  @Test
  fun `floorScrollIndex 空页与负楼号一律不滚`() {
    assertNull(floorScrollIndex(emptyList(), targetFloor = 7, page = 1, rowsPerPage = 20))
    assertNull(floorScrollIndex(louOfPage(1), targetFloor = -1, page = 1, rowsPerPage = 20))
  }

  @Test
  fun `floorScrollIndex 每页楼数不是 20 时按服务端口径算`() {
    val floors = (60L..89L).toList()
    assertEquals(15, floorScrollIndex(floors, targetFloor = 74, page = 3, rowsPerPage = 30))
    assertNull(floorScrollIndex(floors, targetFloor = 74, page = 3, rowsPerPage = 20))
  }
}
