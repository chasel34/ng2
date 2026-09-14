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

@Immutable
data class Ng2nColors(
  val primary: Color,
  val primaryDark: Color,
  val primaryContainer: Color,
  val onPrimary: Color,
  val bg: Color,
  val surface: Color,
  val surface2: Color,
  val menu: Color,
  val fg: Color,
  val fg2: Color,
  val meta: Color,
  val link: Color,
  val tag: Color,
  val accent: Color,
  val danger: Color,
  val divider: Color,
  val quote: Color,
  val scrim: Color,
  val topbar: Color,
  val onTopbar: Color,
  val fab: Color,
  val onFab: Color,
  val track: Color,
  val green: Color = Color(0xFF3F8F5B),
  val greenContainer: Color = Color(0xFFE1EFE3),
  val accentContainer: Color = Color(0xFFF7E8C8),
  val dangerContainer: Color = Color(0xFFF9E2DC),
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
  scrim = Color(0x9E000000),
  topbar = Color(0xFF1C1C1B),
  onTopbar = Color(0xFFF2F0EB),
  fab = Color(0xFF1B8377),
  onFab = Color(0xFFFFFFFF),
  track = Color(0xFF3A3A36),
  green = Color(0xFF5FB27C),
  greenContainer = Color(0xFF213328),
  accentContainer = Color(0xFF3A3020),
  dangerContainer = Color(0xFF3A2522),
)

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

enum class PaletteName { INK, PLAIN, DARK }

fun paletteOf(dark: Boolean, plain: Boolean = false): Ng2nColors = when {
  dark -> DarkColors
  plain -> PlainColors
  else -> LightColors
}

@Immutable
data class TypeToken(val size: TextUnit, val lineHeight: TextUnit)

@Immutable
object Typo {
  val body: TypeToken = TypeToken(15.5.sp, 26.04.sp)
  val quoteBody: TypeToken = TypeToken(14.sp, 22.4.sp)
  val note: TypeToken = TypeToken(12.5.sp, 20.63.sp)
  val notice: TypeToken = TypeToken(13.5.sp, 20.25.sp)
  val section: TypeToken = TypeToken(17.sp, 24.65.sp)
  val listMeta: TypeToken = TypeToken(12.5.sp, 18.sp)
  val meta: TypeToken = TypeToken(11.5.sp, 16.sp)
  val caption: TypeToken = TypeToken(12.5.sp, 18.sp)

  val title: TypeToken = TypeToken(18.sp, 26.sp)

  val subTitle: TypeToken = TypeToken(17.sp, 24.65.sp)
  val tab: TypeToken = TypeToken(15.sp, 21.sp)
  val listTitle: TypeToken = TypeToken(16.sp, 23.2.sp)
  val topicTitle: TypeToken = TypeToken(17.sp, 24.65.sp)
  val gridLabel: TypeToken = TypeToken(14.5.sp, 19.58.sp)
  val drawerItem: TypeToken = TypeToken(15.sp, 21.sp)
  val menuItem: TypeToken = TypeToken(15.5.sp, 22.sp)
  val initial: TypeToken = TypeToken(12.sp, 14.sp)
  val badge: TypeToken = TypeToken(9.sp, 10.sp)
  val unreadBadge: TypeToken = TypeToken(11.sp, 13.sp)
  val dialogTitle: TypeToken = TypeToken(18.sp, 25.sp)
  val dialogBody: TypeToken = TypeToken(13.5.sp, 21.6.sp)
  val dialogAction: TypeToken = TypeToken(14.sp, 20.sp)
  val listSubtitle: TypeToken = TypeToken(12.sp, 17.sp)
  val avatarAbbrev: TypeToken = TypeToken(22.sp, 26.sp)

  val searchSection: TypeToken = TypeToken(16.sp, 23.sp)

  val dialogListItem: TypeToken = TypeToken(14.5.sp, 21.sp)
  val cardMeta: TypeToken = TypeToken(12.sp, 17.sp)
  val folderBadge: TypeToken = TypeToken(10.5.sp, 14.sp)
  val avatarInitial: TypeToken = TypeToken(15.sp, 18.sp)
  val notifyInitial: TypeToken = TypeToken(13.sp, 15.sp)
  val notifyMeta: TypeToken = TypeToken(11.sp, 15.sp)
}

val AvatarColors: List<Color> = listOf(
  Color(0xFF3E6B7E),
  Color(0xFF7E5A3E),
  Color(0xFF5A6E3E),
  Color(0xFF6E3E5A),
  Color(0xFF3E5A7E),
  Color(0xFF7E6B3E),
  Color(0xFF4A4A6E),
)

fun avatarColorFor(key: String): Color {
  var hash = 0L
  for (char in key) hash = (hash * 31 + char.code) % 0xffffff
  return AvatarColors[(hash % AvatarColors.size).toInt()]
}

internal fun avatarColorAt(index: Int): Color = AvatarColors[index]

val MonoFontFamily: FontFamily = FontFamily.Monospace

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

val TopbarOverlay: Color = Color(0x38FFFFFF)

@Immutable
object Elevation {
  val level1: Dp = 4.dp
  val level2: Dp = 12.dp
}

@Immutable
object Radius {
  val xs: Dp = 8.dp
  val sm: Dp = 9.dp
  val md: Dp = 12.dp
  val lg: Dp = 14.dp

  val button: Dp = 15.dp

  val pill: Dp = 16.dp
  val fab: Dp = 19.dp
  val dialog: Dp = 24.dp
  val full: Dp = 999.dp
}

@Immutable
object Spacing {
  val xs: Dp = 4.dp
  val sm: Dp = 8.dp
  val md: Dp = 12.dp
  val lg: Dp = 16.dp
  val xl: Dp = 20.dp
  val row: Dp = 14.dp
  val page: Dp = 18.dp
}

@Immutable
data class TextScale(
  val bodyFontSize: Float = DEFAULT_BODY_FONT_SIZE,
  val bodyLineHeight: Float = DEFAULT_BODY_LINE_HEIGHT,
  val smileyScale: Int = DEFAULT_SMILEY_SCALE,
) {
  val bodyLineHeightSp: Float get() = bodyFontSize * bodyLineHeight

  val smileyHeight: Int get() = (SMILEY_BASE_HEIGHT * smileyScale / 100f).roundToInt()
}

const val DEFAULT_BODY_FONT_SIZE: Float = 15.5f
const val DEFAULT_BODY_LINE_HEIGHT: Float = 1.68f
const val DEFAULT_SMILEY_SCALE: Int = 150

const val SMILEY_BASE_HEIGHT: Int = 16

const val AVATAR_BASE_SIZE: Int = 42

val LocalNg2nColors = staticCompositionLocalOf { LightColors }
val LocalNg2nTitleColors = staticCompositionLocalOf { LightTitleColors }
val LocalTextScale = staticCompositionLocalOf { TextScale() }
