package com.chasel.ng2n.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 39:状态栏图标明暗的判据。
 *
 * 回归的是那条实际发生过的错:判据跟了**页面深浅**(或者系统夜间模式),
 * 于是浅色档把图标刷成黑的,压在深青顶栏上看不见。判据只认顶栏底色。
 */
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
    // 浅色档页面底是奶油;它没当顶栏,所以上面那三条才全是白图标
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
    // 纯蓝与纯绿的通道均值一样(85),人眼亮度差一个数量级
    assertFalse(isLightSurface(Color(0xFF0000FF)))
    assertTrue(isLightSurface(Color(0xFF00FF00)))
  }

  @Test
  fun `相对亮度对得上 WCAG 的定义`() {
    assertEquals(0f, relativeLuminance(Color.Black), 1e-4f)
    assertEquals(1f, relativeLuminance(Color.White), 1e-4f)
    // sRGB 中灰 #808080 的相对亮度 ≈ 0.2159(解伽马之后并不是 0.5)
    assertEquals(0.2159f, relativeLuminance(Color(0xFF808080)), 1e-3f)
    // 主题青 #14796B ≈ 0.1489,低于 0.179 的翻转点
    assertEquals(0.1489f, relativeLuminance(Color(0xFF14796B)), 1e-3f)
    assertTrue(relativeLuminance(Color(0xFF14796B)) < LIGHT_SURFACE_LUMINANCE)
  }
}
