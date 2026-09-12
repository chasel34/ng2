package com.chasel.ng2n.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class ListMetricsTest {

  @Test
  fun `主题列表屏的标题是 17`() {
    assertEquals(17f, Typo.topicTitle.size.value)
    assertEquals(24.65f, Typo.topicTitle.lineHeight.value)
    assertEquals(1.45f, Typo.topicTitle.lineHeight.value / Typo.topicTitle.size.value, 0.001f)
  }

  @Test
  fun `二级列表的标题是 16`() {
    assertEquals(16f, Typo.listTitle.size.value)
    assertEquals(23.2f, Typo.listTitle.lineHeight.value)
  }

  @Test
  fun `两档标题差 6 个百分点`() {
    val ratio = Typo.topicTitle.size.value / Typo.listTitle.size.value
    assertEquals(1.0625f, ratio, 0.0001f)
  }

  @Test
  fun `meta 行与子版块 chip 是 12 点 5`() {
    assertEquals(12.5f, Typo.listMeta.size.value)
    assertEquals(18f, Typo.listMeta.lineHeight.value)
  }

  @Test
  fun `chip 的圆角与条内留白`() {
    assertEquals(9f, Radius.sm.value)
    assertEquals(8f, Spacing.sm.value)
    assertEquals(12f, Spacing.md.value)
    assertEquals(14f, Spacing.row.value)
  }
}
