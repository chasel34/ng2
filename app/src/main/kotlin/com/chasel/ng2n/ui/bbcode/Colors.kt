package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.graphics.Color

/**
 * `[color=…]` / `[size=…]` 的取值归一(RN 侧原件 `src/ui/bbcode/colors.ts`)。
 *
 * NGA 编辑器给的是 CSS 颜色名与百分比字号(官方 `js_bbscode_core.js` 的
 * `ubbcode.fontColor` 24 色 + `ubbcode.fontSize` 五档),但正文里手打什么的都有。
 * 认不出来就返回 null —— 让那段文字按默认样式渲染,而不是把非法值塞进 style。
 *
 * **与 RN 版的一处必要偏离**:RN 直接把 `'skyblue'` 这种关键字交给平台解析,
 * Compose 的 [Color] 没有 CSS 关键字表,所以这里把 24+17 个名字**逐个查出十六进制值**
 * 写死成一张表(取值照 CSS Color Module Level 4,与 RN/Android 平台解析的是同一份标准表)。
 * 名字集合与 RN 版逐字相同 —— 认得出的与认不出的完全一致,只是这边多知道具体色值。
 */

/**
 * 官方调色板的 24 色(`ubbcode.fontColor`),加上正文里常见但不在面板上的那些。
 * 顺序与 RN 版 `NAMED_COLORS` 一致,方便逐条对照。
 */
private val NAMED_COLORS: Map<String, Color> = mapOf(
  // ubbcode.fontColor 原样 24 色
  "skyblue" to Color(0xFF87CEEB),
  "royalblue" to Color(0xFF4169E1),
  "blue" to Color(0xFF0000FF),
  "darkblue" to Color(0xFF00008B),
  "orange" to Color(0xFFFFA500),
  "orangered" to Color(0xFFFF4500),
  "crimson" to Color(0xFFDC143C),
  "red" to Color(0xFFFF0000),
  "firebrick" to Color(0xFFB22222),
  "darkred" to Color(0xFF8B0000),
  "green" to Color(0xFF008000),
  "limegreen" to Color(0xFF32CD32),
  "seagreen" to Color(0xFF2E8B57),
  "teal" to Color(0xFF008080),
  "deeppink" to Color(0xFFFF1493),
  "tomato" to Color(0xFFFF6347),
  "coral" to Color(0xFFFF7F50),
  "purple" to Color(0xFF800080),
  "indigo" to Color(0xFF4B0082),
  "burlywood" to Color(0xFFDEB887),
  "sandybrown" to Color(0xFFF4A460),
  "sienna" to Color(0xFFA0522D),
  "chocolate" to Color(0xFFD2691E),
  "silver" to Color(0xFFC0C0C0),
  // 面板上没有、但老楼层里常见(white 是「防剧透」的写法,故意让人选中才看得见)
  "white" to Color(0xFFFFFFFF),
  "black" to Color(0xFF000000),
  "gray" to Color(0xFF808080),
  "grey" to Color(0xFF808080),
  "yellow" to Color(0xFFFFFF00),
  "pink" to Color(0xFFFFC0CB),
  "brown" to Color(0xFFA52A2A),
  "navy" to Color(0xFF000080),
  "olive" to Color(0xFF808000),
  "maroon" to Color(0xFF800000),
  "cyan" to Color(0xFF00FFFF),
  "magenta" to Color(0xFFFF00FF),
  "lightblue" to Color(0xFFADD8E6),
  "lightgreen" to Color(0xFF90EE90),
  "darkgreen" to Color(0xFF006400),
  "darkorange" to Color(0xFFFF8C00),
  "gold" to Color(0xFFFFD700),
)

private val HEX_COLOR = Regex("""^#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$""")

/**
 * 「防剧透」用的白。`[color=white]` 在奶油/近黑底上都读不出来,是 NGA 上藏答案的写法;
 * 渲染层要认出这一档才知道给不给「点一下看」的入口。
 */
const val SPOILER_COLOR_NAME: String = "white"

/** 认得出的颜色返回可直接用的 [Color],否则 null。 */
fun resolveBBColor(value: String): Color? {
  val color = value.trim().lowercase()
  if (HEX_COLOR.matches(color)) return parseHexColor(color)
  return NAMED_COLORS[color]
}

/** 这一段 `[color]` 是不是防剧透写法(值就是 white,不含 `#ffffff` —— 与 RN 版同口径)。 */
fun isSpoilerColor(value: String): Boolean = value.trim().lowercase() == SPOILER_COLOR_NAME

/** `#rgb` / `#rrggbb` / `#rrggbbaa` → [Color]。调用前已由 [HEX_COLOR] 校验过形状。 */
private fun parseHexColor(hex: String): Color {
  val digits = hex.substring(1)
  return when (digits.length) {
    // #rgb 每位翻倍:#f00 == #ff0000
    3 -> Color(
      red = digits[0].hexDigit() * 17,
      green = digits[1].hexDigit() * 17,
      blue = digits[2].hexDigit() * 17,
    )
    6 -> Color(
      red = digits.substring(0, 2).toInt(16),
      green = digits.substring(2, 4).toInt(16),
      blue = digits.substring(4, 6).toInt(16),
    )
    // CSS 的 #rrggbbaa 是「颜色在前、alpha 在后」,与 Compose 的 ARGB 相反,得重排
    else -> Color(
      red = digits.substring(0, 2).toInt(16),
      green = digits.substring(2, 4).toInt(16),
      blue = digits.substring(4, 6).toInt(16),
      alpha = digits.substring(6, 8).toInt(16),
    )
  }
}

private fun Char.hexDigit(): Int = Character.digit(this, 16)

/** 字号缩放的上下限:再大撑破楼层卡片,再小认不出字。 */
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 2.5f

private val SIZE_VALUE = Regex("""^(\d+(?:\.\d+)?)%?$""")

/**
 * `[size=120%]` → 1.2 倍。官方只发百分比;裸数字按同样的百分比语义处理
 * (`[size=150]` 这种手打形式在老楼层里有)。认不出返回 null。
 */
fun resolveBBSizeScale(value: String): Float? {
  val match = SIZE_VALUE.matchEntire(value.trim()) ?: return null
  val scale = (match.groupValues[1].toFloatOrNull() ?: return null) / 100f
  if (!scale.isFinite() || scale <= 0f) return null
  return scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
