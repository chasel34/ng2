package com.chasel.ng2n.ui.topic

import kotlin.test.Test
import kotlin.test.assertEquals
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

/**
 * 票 57 二轮:翻页要不要走动画([shouldAnimatePageTurn])。
 *
 * 一轮把到底翻页挪到静止态之后,富 trace(`t57-rich-floor.pb`)显示 page 3→4 只剩
 * **一个 18.921ms 的组合帧**,之后 UI 与 RenderThread 双双睡到下一次 ACTION_DOWN ——
 * app surface 整整 353.979ms 没有新帧,录屏记为 265.9ms「无新内容帧」。
 * `scrollToPage` 自己不产帧,新的一页又是静止画面,于是「翻页」在时间轴上是一个点
 * 而不是一段;改成 220ms 的 `animateScrollToPage` 之后这一段才有真实运动。
 */
class PageTurnAnimationTest {

  @Test
  fun `相邻页走动画`() {
    // 到底自动翻页、页码条点「下一页」都是这一类
    assertTrue(shouldAnimatePageTurn(fromPage = 2, toPage = 3))
    assertTrue(shouldAnimatePageTurn(fromPage = 3, toPage = 2), "往回翻同理")
  }

  @Test
  fun `跨页跳转不动画`() {
    // 从第 3 页跳到第 30 页:动画会把中间 27 页一路扫过去,每一页都是一棵要组合的子树
    assertFalse(shouldAnimatePageTurn(fromPage = 2, toPage = 29))
    assertFalse(shouldAnimatePageTurn(fromPage = 29, toPage = 2))
  }

  @Test
  fun `没换页就没有动画可谈`() {
    assertFalse(shouldAnimatePageTurn(fromPage = 4, toPage = 4))
  }

  // -------------------------------------------------------------------------
  // 三轮:判据从接线里抠出来之后
  // -------------------------------------------------------------------------

  @Test
  fun `自动翻页与页码条走动画`() {
    // 到底自动翻页:vm 从第 4 页到第 5 页,pager 还停在第 4 页
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 4))
    // 页码条点「上一页」同理
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 5, toPage = 4, pagerPage = 5))
  }

  @Test
  fun `横滑自己走完就别再滚一次`() {
    // 手指横滑把 pager 送到第 5 页,settledPage 回写 vm.page —— 这一下不该再动 pager
    assertEquals(PageTurn.NONE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 5))
  }

  @Test
  fun `跨页跳转瞬时`() {
    assertEquals(PageTurn.JUMP, pageTurnFor(fromPage = 3, toPage = 30, pagerPage = 3))
  }

  @Test
  fun `相不相邻只看 vm 页码,不看 pager 内部量`() {
    // 二轮拿 pagerState.currentPage 当 from:pager 内部量一旦不是 target-1
    // (横滑收尾、pageCount 变化都会动它),相邻翻页就会掉进瞬时分支 ——
    // 楼层流复验那处 184ms 空洞的两个候选之一。三轮把 from 钉在「上一次呈现的页」上
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 1))
    assertEquals(PageTurn.ANIMATE, pageTurnFor(fromPage = 4, toPage = 5, pagerPage = 9))
  }
}
