package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.theme.Typo
import kotlin.test.Test
import kotlin.test.assertEquals

class MenuMetricsTest {

  @Test
  fun `面板最小宽 186`() {
    assertEquals(186f, MENU_MIN_WIDTH.value)
  }

  @Test
  fun `面板最高 520 超了就滚`() {
    assertEquals(520f, MENU_MAX_HEIGHT.value)
  }

  @Test
  fun `条目高 50 左右内距 22`() {
    assertEquals(50f, MENU_ITEM_HEIGHT.value)
    assertEquals(22f, MENU_ITEM_PADDING.value)
  }

  @Test
  fun `条目字号是 menuItem 那一档`() {
    assertEquals(15.5f, Typo.menuItem.size.value)
    assertEquals(22f, Typo.menuItem.lineHeight.value)
  }

  @Test
  fun `186 的面板给文字留 142`() {
    assertEquals(142f, MENU_MIN_WIDTH.value - MENU_ITEM_PADDING.value * 2)
  }
}
