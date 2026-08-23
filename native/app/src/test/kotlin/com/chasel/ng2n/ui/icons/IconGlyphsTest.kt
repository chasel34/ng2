package com.chasel.ng2n.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 票 40:图标集与 RN 侧同形。
 *
 * 手画版与 RN 版画的**不是同一个东西**(线框 vs 实心、软盘 vs 下箭头、加号在人形左还是右……
 * 逐屏对照数出 17 处),所以形状不再手画,而是把 RN 那份字体
 * (`assets/fonts/MaterialIconsOutlined-Regular.otf`)里同名字形的轮廓导成路径常量
 * (`native/tools/gen_icon_paths.py` → [ICON_PATHS])。
 *
 * 能在 JVM 上钉住的是这三件:**表是齐的**(每颗枚举都有路径)、**表是同一张**
 * (键 = RN `ICON_GLYPHS` 的名字)、**尺寸是同一档**(轮廓都落在 24 格设计栅格里,
 * 放大倍数 = 边长 / 24,所以视觉尺寸与 RN 的 `fontSize` 一致)。
 * 画出来长什么样只有真机能看,但「拿错图标」这一类(票 40 最要紧的那条)在这里就挡住了。
 */
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
    // RN 侧 `src/ui/icons.generated.ts` 的键是同一批名字,两版靠这个约定对齐
    assertEquals("arrow_back", Ng2nIcon.ARROW_BACK.glyphName)
    assertEquals("local_fire_department", Ng2nIcon.LOCAL_FIRE_DEPARTMENT.glyphName)
    assertEquals("sticky_note_2", Ng2nIcon.STICKY_NOTE_2.glyphName)
  }

  @Test
  fun `轮廓都落在 24 格设计栅格里`() {
    // 落在栅格里 = 视觉尺寸只由调用方的 size 决定。手画版就栽在这:通知空态那颗铃铛
    // 只画了 0.36 个格,同一个 40dp 的框里比别的屏小 22%(票 40 表里那一行)。
    for (icon in Ng2nIcon.entries) {
      val data = ICON_PATHS.getValue(icon.glyphName)
      val numbers = NUMBER.findAll(data).map { it.value.toFloat() }.toList()
      assertTrue(numbers.isNotEmpty(), "${icon.glyphName} 的路径是空的")
      val min = numbers.min()
      val max = numbers.max()
      // 少数字形(warning / campaign)会贴着边缘出去零点几,给半格容差
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

  /**
   * 导出变换钉一颗:字体是 512 upem、基线在 0、y 向上,转成 24 视口就是
   * `x * 24 / 512` 与 `24 - y * 24 / 512`。变换写错(比如漏了翻转)图标会上下颠倒,
   * 那是模拟器上一眼能看出、JVM 上只有这条能挡住的事。
   */
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
