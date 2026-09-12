package com.chasel.ng2n.ui.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndReachedTest {

  private val total = 30

  @Test
  fun `滚停了且末项进视口才翻页`() {
    assertTrue(shouldTurnPageAtEnd(lastVisibleIndex = total - 1, totalItemsCount = total, scrolling = false))
  }

  @Test
  fun `还在滚就不翻`() {
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total - 1, totalItemsCount = total, scrolling = true))
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total + 4, totalItemsCount = total, scrolling = true))
  }

  @Test
  fun `没到底不翻`() {
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = total - 2, totalItemsCount = total, scrolling = false))
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = 0, totalItemsCount = total, scrolling = false))
  }

  @Test
  fun `列表还没量出来时不翻`() {
    assertFalse(shouldTurnPageAtEnd(lastVisibleIndex = -1, totalItemsCount = 0, scrolling = false))
  }
}

class PageTurnAnimationTest {

  @Test
  fun `相邻页走动画`() {
    assertTrue(shouldAnimatePageTurn(fromPage = 2, toPage = 3))
    assertTrue(shouldAnimatePageTurn(fromPage = 3, toPage = 2), "往回翻同理")
  }

  @Test
  fun `跨页跳转不动画`() {
    assertFalse(shouldAnimatePageTurn(fromPage = 2, toPage = 29))
    assertFalse(shouldAnimatePageTurn(fromPage = 29, toPage = 2))
  }

  @Test
  fun `没换页就没有动画可谈`() {
    assertFalse(shouldAnimatePageTurn(fromPage = 4, toPage = 4))
  }

  @Test
  fun `自动翻页与页码条走动画`() {
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 4))
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 5, toPage = 4, pagerPage = 5))
  }

  @Test
  fun `横滑自己走完就别再滚一次`() {
    assertEquals(PageTurn.NONE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 5))
  }

  @Test
  fun `跨页跳转瞬时`() {
    assertEquals(PageTurn.JUMP, pageTurnFor(fromPage = 3, toPage = 30, pagerPage = 3))
  }

  @Test
  fun `相不相邻只看 vm 页码,不看 pager 内部量`() {
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 1))
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 9))
  }
}
