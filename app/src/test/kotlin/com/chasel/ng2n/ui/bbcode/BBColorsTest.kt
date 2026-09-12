package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BBColorsTest {

  @Test
  fun `认官方调色板的颜色名`() {
    assertEquals(Color(0xFF87CEEB), resolveBBColor("skyblue"))
    assertEquals(Color(0xFFDC143C), resolveBBColor("crimson"))
    assertEquals(Color(0xFFF4A460), resolveBBColor("sandybrown"))
  }

  @Test
  fun `大小写与空白都收`() {
    assertEquals(Color(0xFFFF0000), resolveBBColor(" Red "))
  }

  @Test
  fun `认十六进制色值`() {
    assertEquals(Color(0xFFFF0000), resolveBBColor("#FF0000"))
    assertEquals(Color(0xFFFF0000), resolveBBColor("#f00"))
    assertEquals(Color(0x80FF0000), resolveBBColor("#ff000080"))
  }

  @Test
  fun `认不出的一律返回 null 不把脏值塞进样式`() {
    for (bad in listOf("", "rgb(1,2,3)", "javascript:x", "#gg0000", "红色", "#ff00")) {
      assertNull(resolveBBColor(bad), bad)
    }
  }

  @Test
  fun `防剧透只认色名 white 不认等价的十六进制`() {
    assertTrue(isSpoilerColor("white"))
    assertTrue(isSpoilerColor(" White "))
    assertTrue(!isSpoilerColor("#ffffff"))
    assertTrue(!isSpoilerColor("red"))
  }

  @Test
  fun `百分比换算成倍数`() {
    assertEquals(1f, resolveBBSizeScale("100%"))
    assertEquals(1.5f, resolveBBSizeScale("150%"))
  }

  @Test
  fun `裸数字按同样的百分比语义`() {
    assertEquals(1.2f, resolveBBSizeScale("120"))
  }

  @Test
  fun `过大过小都夹到上下限 免得撑破楼层卡片`() {
    assertEquals(2.5f, resolveBBSizeScale("900%"))
    assertEquals(0.6f, resolveBBSizeScale("10%"))
  }

  @Test
  fun `字号认不出返回 null`() {
    for (bad in listOf("", "large", "-50%", "0%", "12px")) {
      assertNull(resolveBBSizeScale(bad), bad)
    }
  }
}
