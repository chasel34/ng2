package com.chasel.ng2n.ui.perf

import kotlin.test.Test
import kotlin.test.assertEquals

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
