package com.chasel.ng2n.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 设计 token —— 唯一颜色/字号/圆角/间距来源(RN 侧原件 `src/ui/tokens.ts`)。
 *
 * 数值照抄 `design/project/NGA客户端.dc.html`:颜色取 `:root`(浅)与 `.omdark`(深)
 * 两个声明块。**页面里不许再写魔法值**,缺档往这里加并注明设计稿出处。
 *
 * 票 11 只搬渲染器用得到的那一部分(颜色全表 + 正文相关字号档 + 圆角 + 间距);
 * 完整 token 表(全部 40 余档字号、阴影、彩色标题色)是票 17 的活,那时把这里补齐即可,
 * 不要另起一份。RN 侧 `theme.ts` 的 `paletteOf(scheme, style)` 语义一并保留:
 * 深色一档到底,浅色分 `ink`(奶油)与 `plain`(纯白)两种风格。
 */

@Immutable
data class Ng2nColors(
  /** 顶栏 / Tab / FAB / 强调文字 */
  val primary: Color,
  /** primary 的加深态,用于按下/描边 */
  val primaryDark: Color,
  /** 版块图标底 / 提示条底 */
  val primaryContainer: Color,
  /** 落在 primary 上的文字与图标 */
  val onPrimary: Color,
  /** 页面背景(奶油 / 近黑) */
  val bg: Color,
  /** 主楼楼层 / 抽屉 / 卡片 */
  val surface: Color,
  /** 公告条 / 折叠按钮 / 贴条区 */
  val surface2: Color,
  /** 弹出菜单 / 对话框 */
  val menu: Color,
  /** 标题 / 正文 */
  val fg: Color,
  /** 次级正文 / 抽屉图标 */
  val fg2: Color,
  /** 时间 / 级别 / 威望 */
  val meta: Color,
  /** 作者名 / 最后回复人 / 链接 */
  val link: Color,
  /** 标题后的方括号分类 */
  val tag: Color,
  /** 标签 + 号 / 公告图标 */
  val accent: Color,
  /** [锁定] / 红色标题 / 删除 */
  val danger: Color,
  /** 列表分隔线 */
  val divider: Color,
  /** 引用块底色 */
  val quote: Color,
  /** 抽屉 / 对话框遮罩 */
  val scrim: Color,
  /** 顶栏底色:浅色下同 primary,深色下压成近黑 */
  val topbar: Color,
  /** 顶栏上的文字与图标 */
  val onTopbar: Color,
  /** FAB 底色 */
  val fab: Color,
  /** FAB 上的图标 */
  val onFab: Color,
  /** 进度条 / 滑块的未填充轨道 */
  val track: Color,
)

val LightColors: Ng2nColors = Ng2nColors(
  primary = Color(0xFF14796B),
  primaryDark = Color(0xFF0F5D53),
  primaryContainer = Color(0xFFD8EAE5),
  onPrimary = Color(0xFFFFFFFF),
  bg = Color(0xFFFCF4E1),
  surface = Color(0xFFFFFBF0),
  surface2 = Color(0xFFF5F0E0),
  menu = Color(0xFFF7F4EE),
  fg = Color(0xFF1E1C19),
  fg2 = Color(0xFF57534A),
  meta = Color(0xFFA39D8E),
  link = Color(0xFF3A5A7A),
  tag = Color(0xFFB7B0A0),
  accent = Color(0xFFE09A2A),
  danger = Color(0xFFDE3B2C),
  divider = Color(0xFFEBE3CE),
  quote = Color(0xFFF1EBD6),
  // rgba(30, 26, 16, 0.42) —— alpha 0.42 ≈ 0x6B
  scrim = Color(0x6B1E1A10),
  topbar = Color(0xFF14796B),
  onTopbar = Color(0xFFFFFFFF),
  fab = Color(0xFF14796B),
  onFab = Color(0xFFFFFFFF),
  track = Color(0xFFDCD4BE),
)

val DarkColors: Ng2nColors = Ng2nColors(
  primary = Color(0xFF1E9384),
  primaryDark = Color(0xFF17766A),
  primaryContainer = Color(0xFF1E3A35),
  onPrimary = Color(0xFFFFFFFF),
  bg = Color(0xFF1C1C1B),
  surface = Color(0xFF232322),
  surface2 = Color(0xFF292928),
  menu = Color(0xFF2E2E2C),
  fg = Color(0xFFE9E7E2),
  fg2 = Color(0xFFB0ACA3),
  meta = Color(0xFF6F6C65),
  link = Color(0xFF8AA6C8),
  tag = Color(0xFF5F5C55),
  accent = Color(0xFFD6942E),
  danger = Color(0xFFE2574C),
  divider = Color(0xFF333330),
  quote = Color(0xFF262625),
  // rgba(0, 0, 0, 0.62) —— alpha 0.62 ≈ 0x9E
  scrim = Color(0x9E000000),
  topbar = Color(0xFF1C1C1B),
  onTopbar = Color(0xFFF2F0EB),
  fab = Color(0xFF1B8377),
  onFab = Color(0xFFFFFFFF),
  track = Color(0xFF3A3A36),
)

/**
 * 「纯白」主题风格(设计稿「主题风格」对话框第二项:「白底 + 深绿强调」)。
 * 设计稿没有对应声明块,按那句副标题从浅色档改:奶油底压成白与近白灰,墨绿强调色不动。
 */
val PlainColors: Ng2nColors = LightColors.copy(
  bg = Color(0xFFFFFFFF),
  surface = Color(0xFFFFFFFF),
  surface2 = Color(0xFFF2F2F0),
  menu = Color(0xFFFFFFFF),
  quote = Color(0xFFF4F4F2),
  divider = Color(0xFFE6E4DF),
  track = Color(0xFFDDDBD5),
  tag = Color(0xFFB4B2AC),
  meta = Color(0xFF9C9A93),
)

/** 一套配色的身份。`INK`/`DARK` 是设计稿声明的两套,`PLAIN` 是那一档白底。 */
enum class PaletteName { INK, PLAIN, DARK }

/** 按「深浅 + 主题风格」取配色。深色下没有风格之分——夜间近黑就是夜间模式本身。 */
fun paletteOf(dark: Boolean, plain: Boolean = false): Ng2nColors = when {
  dark -> DarkColors
  plain -> PlainColors
  else -> LightColors
}

/**
 * 字号档位。RN 侧 token 表把行高写成绝对像素,这里同样落成 sp——
 * Compose 的 `lineHeight` 也吃绝对值,倍数换算在 token 表里做完。
 */
@Immutable
data class TypeToken(val size: TextUnit, val lineHeight: TextUnit)

/** 票 11 用得到的档位。其余档(顶栏、列表、对话框……)票 17 铺屏时按设计稿补。 */
@Immutable
object Typo {
  /** 楼层正文 15.5 · 1.68 */
  val body: TypeToken = TypeToken(15.5.sp, 26.04.sp)
  /** 引用块正文 14 · 1.6 */
  val quoteBody: TypeToken = TypeToken(14.sp, 22.4.sp)
  /** 贴条 12.5 · 1.65 */
  val note: TypeToken = TypeToken(12.5.sp, 20.63.sp)
  /** 公告条 / 折叠提要行 13.5 · 1.5 */
  val notice: TypeToken = TypeToken(13.5.sp, 20.25.sp)
  /** 分组标题 / `[h]` 小标题 17 · 1.45 */
  val section: TypeToken = TypeToken(17.sp, 24.65.sp)
  /** 主题行信息行 / 折叠卡右侧动作 12.5 */
  val listMeta: TypeToken = TypeToken(12.5.sp, 18.sp)
  /** 级别 / 威望 / 时间 11.5 */
  val meta: TypeToken = TypeToken(11.5.sp, 16.sp)
  /** 抽屉分区小标题 / 骰子 ROLL 角标 12.5 · 700 */
  val caption: TypeToken = TypeToken(12.5.sp, 18.sp)
}

/** 等宽字体。设计稿用 `ui-monospace,Menlo,monospace`,Android 侧就是系统 monospace。 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

@Immutable
object Radius {
  /** 页码格 / 签名框 / 版主标签 */
  val xs: Dp = 8.dp
  /** 分类标签 */
  val sm: Dp = 9.dp
  /** 提示条 / 引用块 / 折叠按钮 */
  val md: Dp = 12.dp
  /** 菜单 / 卡片 / Snackbar */
  val lg: Dp = 14.dp
}

@Immutable
object Spacing {
  val xs: Dp = 4.dp
  val sm: Dp = 8.dp
  val md: Dp = 12.dp
  val lg: Dp = 16.dp
  val xl: Dp = 20.dp
  /** 设计稿反复出现的列表行内距 */
  val row: Dp = 14.dp
  /** 页面左右留白 */
  val page: Dp = 18.dp
}

/**
 * 「字体和头像大小」滑块(RN 侧票 22)的当前取值。
 *
 * 为什么是 CompositionLocal 而不是逐层传参:一个字号要穿过引用块、折叠块、
 * 表格单元格好几层。默认值就是 RN 侧 `DEFAULT_SETTINGS.appearance` 那一档,
 * 设置屏(票 17)在根上 provide 用户值。
 *
 * **注意**:渲染模型是**后台**建的([com.chasel.ng2n.ui.bbcode.RenderModelBuilder]),
 * 那时读不到 CompositionLocal。所以同一组数值还要经
 * [com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions] 给建模器传一份——
 * 这里的 local 只服务「已经在 composition 里」的那部分(表情内联高度、默认 TextStyle)。
 */
@Immutable
data class TextScale(
  /** 帖子内字体大小,滑块 12–22,默认 15.5 */
  val bodyFontSize: Float = DEFAULT_BODY_FONT_SIZE,
  /** 主题详情页行高倍数,滑块 1.3–2.2,默认 1.68 */
  val bodyLineHeight: Float = DEFAULT_BODY_LINE_HEIGHT,
  /** 表情大小百分比,滑块 80–220,默认 150 */
  val smileyScale: Int = DEFAULT_SMILEY_SCALE,
) {
  /** 正文行高的绝对值(RN 侧 `bodyFontSize * bodyLineHeight`)。 */
  val bodyLineHeightSp: Float get() = bodyFontSize * bodyLineHeight

  /** 正文里表情的显示高度(RN 侧 `smileyHeightOf`:16 基准按百分比取整)。 */
  val smileyHeight: Int get() = (SMILEY_BASE_HEIGHT * smileyScale / 100f).roundToInt()
}

const val DEFAULT_BODY_FONT_SIZE: Float = 15.5f
const val DEFAULT_BODY_LINE_HEIGHT: Float = 1.68f
const val DEFAULT_SMILEY_SCALE: Int = 150

/** 表情高度基准(RN 侧 `SMILEY_BASE_HEIGHT`)。150% = 24,与一行正文 26 相当。 */
const val SMILEY_BASE_HEIGHT: Int = 16

/** 头像边长基准(RN 侧 `AVATAR_BASE_SIZE`)。票 13 的楼层头要用。 */
const val AVATAR_BASE_SIZE: Int = 42

val LocalNg2nColors = staticCompositionLocalOf { LightColors }
val LocalTextScale = staticCompositionLocalOf { TextScale() }
