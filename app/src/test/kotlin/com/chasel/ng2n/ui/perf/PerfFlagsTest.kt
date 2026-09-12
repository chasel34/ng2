package com.chasel.ng2n.ui.perf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 59 二轮的两个测量口子**默认必须等于发布行为**。
 *
 * 它们是「改一个字面量、重打一包、再采一次 trace」用的,做完实验很容易忘了改回来 ——
 * 而两条都会改窗口/层结构,漏出去一条就是黑闪或少一层投影。这里钉死出厂值。
 */
class PerfFlagsTest {

  @Test
  fun 窗口底色默认不置空() {
    assertEquals(false, PerfFlags.BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME)
  }

  @Test
  fun 抽屉面板默认带投影() {
    assertEquals(true, PerfFlags.DRAWER_PANEL_SHADOW)
  }
}
