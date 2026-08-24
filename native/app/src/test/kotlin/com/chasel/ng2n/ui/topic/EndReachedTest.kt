package com.chasel.ng2n.ui.topic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 57:楼层流「到底自动翻页」的判据([shouldTurnPageAtEnd])。
 *
 * 翻页走的是 `pagerState.scrollToPage()` —— 瞬时换页、换一棵子树、纵向偏移从 0 起。
 * 在 fling 中段发这一下,速度当场归零、视口跳到新页顶部,真机逐帧上就是那段
 * 0.43~0.56s 的静止。所以判据里必须带上「这一把已经滚停了」。
 */
class EndReachedTest {

  private val total = 30

  @Test
  fun `滚停了且末项进视口才翻页`() {
    assertTrue(shouldTurnPageAtEnd(lastVisibleIndex = total - 1, totalItemsCount = total, scrolling = false))
  }

  @Test
  fun `还在滚就不翻`() {
    // 票 57 的现场:fling 还有 10k px_s 的时候 footer 露头了
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total - 1, totalItemsCount = total, scrolling = true))
    // 手指还按着(拖拽也算 isScrollInProgress)同理:抬手落定再翻
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total + 4, totalItemsCount = total, scrolling = true))
  }

  @Test
  fun `没到底不翻`() {
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total - 2, totalItemsCount = total, scrolling = false))
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = 0, totalItemsCount = total, scrolling = false))
  }

  @Test
  fun `列表还没量出来时不翻`() {
    // totalItemsCount 为 0 时 lastVisibleIndex 只可能是 -1,别让 -1 >= -1 变成「到底了」
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = -1, totalItemsCount = 0, scrolling = false))
  }
}
