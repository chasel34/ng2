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

  // ---- 票 16 追加(RN 侧 `src/ui/tokens.ts` 同名档,值一字未改)----

  /** 顶栏标题 18 · 600 · letterSpacing 0.2 */
  val title: TypeToken = TypeToken(18.sp, 26.sp)
  /** 二级页顶栏标题 / 分组标题 17(= [section]) */
  val subTitle: TypeToken = TypeToken(17.sp, 24.65.sp)
  /** Tab / 分段控件 15 · 600 */
  val tab: TypeToken = TypeToken(15.sp, 21.sp)
  /** 列表主题标题 16 · 1.45(热帖 / 精华区那类二级列表) */
  val listTitle: TypeToken = TypeToken(16.sp, 23.2.sp)
  /** 主题列表屏(设计稿 `isList`)的标题 17 · 1.45 */
  val topicTitle: TypeToken = TypeToken(17.sp, 24.65.sp)
  /** 版块宫格里的版块名 14.5 · 1.35 */
  val gridLabel: TypeToken = TypeToken(14.5.sp, 19.58.sp)
  /** 抽屉条目 15 */
  val drawerItem: TypeToken = TypeToken(15.sp, 21.sp)
  /** 弹出菜单条目 15.5 */
  val menuItem: TypeToken = TypeToken(15.5.sp, 22.sp)
  /** 版块图标的首字占位 12 · 700 */
  val initial: TypeToken = TypeToken(12.sp, 14.sp)
  /** 分组标题前的圆形角标 9 · 700 */
  val badge: TypeToken = TypeToken(9.sp, 10.sp)
  /** 未读数角标 11 · 700 */
  val unreadBadge: TypeToken = TypeToken(11.sp, 13.sp)
  /** 对话框标题 18 · 600 */
  val dialogTitle: TypeToken = TypeToken(18.sp, 25.sp)
  /** 对话框正文 13.5 · 1.6 */
  val dialogBody: TypeToken = TypeToken(13.5.sp, 21.6.sp)
  /** 对话框按钮 14 · 600 */
  val dialogAction: TypeToken = TypeToken(14.sp, 20.sp)
  /** 二级列表页副标题条 12 / 卡片副行 12 */
  val listSubtitle: TypeToken = TypeToken(12.sp, 17.sp)
  /** 账号头像里的缩写 22 · 700(设计稿抽屉账号头) */
  val avatarAbbrev: TypeToken = TypeToken(22.sp, 26.sp)

  // ---- 票 17a 追加(搜索 / 收藏 / 历史 / 缓存 / 通知,RN 侧 `src/ui/tokens.ts` 同名档)----

  /** 搜索屏「搜索选项 / 搜索历史」小节标题 16 · 600(设计稿 isSearch 屏) */
  val searchSection: TypeToken = TypeToken(16.sp, 23.sp)
  /** 对话框列表条目 / 搜索选项与历史词 14.5(设计稿「收藏到…」多选夹那档) */
  val dialogListItem: TypeToken = TypeToken(14.5.sp, 21.sp)
  /** 卡片副行 / 二级列表副标题条 12(设计稿 isFolders 卡片与 listSub) */
  val cardMeta: TypeToken = TypeToken(12.sp, 17.sp)
  /** 收藏夹「默认」徽标 10.5 · 700(设计稿 isFolders 屏) */
  val folderBadge: TypeToken = TypeToken(10.5.sp, 14.sp)
  /** 头像占位的首字 15 · 700(设计稿 isArticle 的楼层头) */
  val avatarInitial: TypeToken = TypeToken(15.sp, 18.sp)
  /** 通知条目 36 见方头像里的首字 13 · 700(设计稿 isNotify 屏) */
  val notifyInitial: TypeToken = TypeToken(13.sp, 15.sp)
  /** 通知条目第三行「第 N 页 · 时间」11(设计稿 isNotify 屏) */
  val notifyMeta: TypeToken = TypeToken(11.sp, 15.sp)
}

/**
 * 头像占位的一档底色 —— 直译 RN 侧 `tokens.ts` 的 `avatarColors`。
 * 「同一个人每次都同一个颜色」就够,所以取色用的是个逐字符累加的弱散列
 * ([avatarColorFor]),不需要抗碰撞。
 */
val AvatarColors: List<Color> = listOf(
  Color(0xFF3E6B7E),
  Color(0xFF7E5A3E),
  Color(0xFF5A6E3E),
  Color(0xFF6E3E5A),
  Color(0xFF3E5A7E),
  Color(0xFF7E6B3E),
  Color(0xFF4A4A6E),
)

/**
 * 按用户 key 稳定取一档占位底色(RN 侧 `ui/avatar.tsx` 的 `avatarColorFor`)。
 *
 * 散列照抄 TS 那一行:`hash = (hash * 31 + charCodeAt(i)) % 0xffffff` ——
 * `charCodeAt` 是 **UTF-16 码元**,Kotlin 的 `Char.code` 正好同义,
 * 所以同一个 uid 在两版里落在同一档色上。
 */
fun avatarColorFor(key: String): Color {
  var hash = 0L
  for (char in key) hash = (hash * 31 + char.code) % 0xffffff
  return AvatarColors[(hash % AvatarColors.size).toInt()]
}

/**
 * 第 [index] 档占位底色。单测按 RN 侧算出来的档位对拍用 ——
 * 生产代码一律走 [avatarColorFor],不要按下标取色。
 */
internal fun avatarColorAt(index: Int): Color = AvatarColors[index]

/** 等宽字体。设计稿用 `ui-monospace,Menlo,monospace`,Android 侧就是系统 monospace。 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

/**
 * 彩色标题(CONTEXT.md「彩色标题」)的五档色。掩码 → 档位在
 * [com.chasel.ng2n.core.local.TitleStyle],这里只管画成什么颜色。
 * 值照抄 RN 侧 `tokens.ts` 的 `lightTitleColors` / `darkTitleColors`。
 */
@Immutable
data class Ng2nTitleColors(
  val red: Color,
  val blue: Color,
  val green: Color,
  val orange: Color,
  val silver: Color,
)

val LightTitleColors: Ng2nTitleColors = Ng2nTitleColors(
  red = LightColors.danger,
  blue = LightColors.link,
  green = Color(0xFF3F8F5B),
  orange = LightColors.accent,
  silver = LightColors.meta,
)

val DarkTitleColors: Ng2nTitleColors = Ng2nTitleColors(
  red = DarkColors.danger,
  blue = DarkColors.link,
  green = Color(0xFF5FB27C),
  orange = DarkColors.accent,
  silver = DarkColors.meta,
)

fun titleColorsOf(dark: Boolean): Ng2nTitleColors = if (dark) DarkTitleColors else LightTitleColors

/**
 * 顶栏上「当前选中」那一格的底色(设计稿页码格与抽屉当前账号都用它)。
 * 不进 [Ng2nColors]:它不是 `:root` 里声明的一档色,而是压在顶栏上的一层半透明白,
 * 浅深两套配色下都是同一个值 —— 顶栏本身已经分别是墨绿和近黑了。
 */
val TopbarOverlay: Color = Color(0x38FFFFFF)

/**
 * 阴影档位。设计稿是 CSS `box-shadow`(带色带模糊半径),Compose 的
 * `Modifier.shadow(elevation)` 只吃一个高度值 —— 按模糊半径 12 / 28 折成两档。
 */
@Immutable
object Elevation {
  /** `0 2px 12px` —— FAB / 卡片 */
  val level1: Dp = 4.dp
  /** `0 8px 28px` —— 抽屉面板 / 对话框 / snackbar */
  val level2: Dp = 12.dp
}

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

  // ---- 票 16 追加 ----

  /** 整宽动作按钮(设计稿 isError 屏) */
  val button: Dp = 15.dp
  /** 胶囊按钮(设计稿 isSubboards 的订阅钮:32 高配 16 圆角) */
  val pill: Dp = 16.dp
  val fab: Dp = 19.dp
  val dialog: Dp = 24.dp
  /** 圆形图标按钮(设计稿里是 46/44 见方配 23/22 圆角) */
  val full: Dp = 999.dp
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
val LocalNg2nTitleColors = staticCompositionLocalOf { LightTitleColors }
val LocalTextScale = staticCompositionLocalOf { TextScale() }
