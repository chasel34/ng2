package com.chasel.ng2n.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemBarsTest {

  @Test
  fun `浅色档顶栏是深青,状态栏要白图标`() {
    assertFalse(appearanceLightStatusBarsFor(LightColors.topbar))
  }

  @Test
  fun `纯白风格的顶栏仍是深青,照样白图标`() {
    assertFalse(appearanceLightStatusBarsFor(PlainColors.topbar))
  }

  @Test
  fun `夜间档顶栏近黑,状态栏要白图标`() {
    assertFalse(appearanceLightStatusBarsFor(DarkColors.topbar))
  }

  @Test
  fun `判据跟的是顶栏不是页面底 —— 奶油底真当了顶栏才该翻成深图标`() {
    assertTrue(appearanceLightStatusBarsFor(LightColors.bg))
    assertTrue(appearanceLightStatusBarsFor(PlainColors.bg))
    assertFalse(appearanceLightStatusBarsFor(DarkColors.bg))
  }

  @Test
  fun `纯白要深图标,纯黑要白图标`() {
    assertTrue(isLightSurface(Color.White))
    assertFalse(isLightSurface(Color.Black))
  }

  @Test
  fun `算的是亮度不是通道均值`() {
    assertFalse(isLightSurface(Color(0xFF0000FF)))
    assertTrue(isLightSurface(Color(0xFF00FF00)))
  }

  @Test
  fun `相对亮度对得上 WCAG 的定义`() {
    assertEquals(0f, relativeLuminance(Color.Black), 1e-4f)
    assertEquals(1f, relativeLuminance(Color.White), 1e-4f)
    assertEquals(0.2159f, relativeLuminance(Color(0xFF808080)), 1e-3f)
    assertEquals(0.1489f, relativeLuminance(Color(0xFF14796B)), 1e-3f)
    assertTrue(relativeLuminance(Color(0xFF14796B)) < LIGHT_SURFACE_LUMINANCE)
  }
}
