package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.theme.Typo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 43:弹出菜单的尺寸档,与 RN 侧 `src/ui/menu.tsx` 逐值对齐。
 *
 * 对照图上量到的:Expo 的两个菜单都是 242px(半缩放)≈ 184dp,原生的顶栏 kebab 是
 * 206dp、楼层长按菜单是 394dp —— 后者几乎占满屏宽,同一个 app 里两个菜单差了近一倍。
 * 原因是两处各写各的:kebab 写死 `width(208.dp)`,楼层那份只有 `defaultMinSize(186)`
 * 而条目是 `fillMaxWidth`,于是被父约束撑到满屏。
 *
 * 现在两处共用这几档,数值就是 RN 的 `minWidth: 186` / `maxHeight: 520` /
 * `item.height: 50` / `item.paddingHorizontal: 22`。
 */
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

  /**
   * 条目字号:两个菜单都走 `menuItem` 那一档 15.5。
   * 楼层菜单原来用的是提示条的 13.5(复量:同一条「贴条」Expo 39px / 原生 35px)。
   */
  @Test
  fun `条目字号是 menuItem 那一档`() {
    assertEquals(15.5f, Typo.menuItem.size.value)
    assertEquals(22f, Typo.menuItem.lineHeight.value)
  }

  /**
   * 「最小宽 + 内容撑宽」的下界:最长条目也得比 186 窄才轮得到 minWidth 生效。
   * 这里钉的是**换算关系本身** —— 条目宽 = 文字宽 + 44,所以 186 的面板留给文字 142dp,
   * 即 15.5sp 下九个汉字左右;菜单文案(「按最后回复排序」七字)都在这个量级内。
   */
  @Test
  fun `186 的面板给文字留 142`() {
    assertEquals(142f, MENU_MIN_WIDTH.value - MENU_ITEM_PADDING.value * 2)
  }
}
