package com.chasel.ng2n.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconGlyphsTest {

  @Test
  fun `每颗枚举都有路径数据`() {
    val missing = Ng2nIcon.entries.filter { it.glyphName !in ICON_PATHS }
    assertTrue(missing.isEmpty(), "没有路径数据的图标:${missing.map { it.glyphName }}")
  }

  @Test
  fun `枚举与路径表一一对应 没有多余字形`() {
    assertEquals(
      Ng2nIcon.entries.map { it.glyphName }.toSet(),
      ICON_PATHS.keys,
      "枚举与生成表对不上:改了枚举要重跑 native/tools/gen_icon_paths.py",
    )
  }

  @Test
  fun `枚举名小写就是 Material 图标名`() {
    assertEquals("arrow_back", Ng2nIcon.ARROW_BACK.glyphName)
    assertEquals("local_fire_department", Ng2nIcon.LOCAL_FIRE_DEPARTMENT.glyphName)
    assertEquals("sticky_note_2", Ng2nIcon.STICKY_NOTE_2.glyphName)
  }

  @Test
  fun `轮廓都落在 24 格设计栅格里`() {
    for (icon in Ng2nIcon.entries) {
      val data = ICON_PATHS.getValue(icon.glyphName)
      val numbers = NUMBER.findAll(data).map { it.value.toFloat() }.toList()
      assertTrue(numbers.isNotEmpty(), "${icon.glyphName} 的路径是空的")
      val min = numbers.min()
      val max = numbers.max()
      assertTrue(min >= -0.5f, "${icon.glyphName} 的坐标越界:$min")
      assertTrue(max <= 24.5f, "${icon.glyphName} 的坐标越界:$max")
    }
  }

  @Test
  fun `字形的量级像一颗图标 不是被压扁的一条`() {
    for (icon in Ng2nIcon.entries) {
      val data = ICON_PATHS.getValue(icon.glyphName)
      val numbers = NUMBER.findAll(data).map { it.value.toFloat() }.toList()
      assertTrue(numbers.max() - numbers.min() > 6f, "${icon.glyphName} 的轮廓太小,像是导错了")
    }
  }

  @Test
  fun `arrow_back 的轮廓与字体里那颗一致`() {
    assertEquals(
      "M20.02 11.02H7.83L13.41 5.39L12 3.98L3.98 12L12 20.02L13.41 18.61L7.83 12.98H20.02V11.02Z",
      ICON_PATHS.getValue("arrow_back"),
    )
  }

  @Test
  fun `add 的轮廓与字体里那颗一致`() {
    assertEquals(
      "M18.98 12.98H12.98V18.98H11.02V12.98H5.02V11.02H11.02V5.02H12.98V11.02H18.98V12.98Z",
      ICON_PATHS.getValue("add"),
    )
  }

  @Test
  fun `视口就是 Material 的 24 格`() {
    assertEquals(24f, ICON_VIEWPORT)
  }

  private companion object {
    val NUMBER = Regex("-?\\d+(\\.\\d+)?")
  }
}
