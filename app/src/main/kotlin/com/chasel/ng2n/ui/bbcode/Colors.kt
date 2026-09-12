package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.graphics.Color

private val NAMED_COLORS: Map<String, Color> = mapOf(
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

const val SPOILER_COLOR_NAME: String = "white"

fun resolveBBColor(value: String): Color? {
  val color = value.trim().lowercase()
  if (HEX_COLOR.matches(color)) return parseHexColor(color)
  return NAMED_COLORS[color]
}

fun isSpoilerColor(value: String): Boolean = value.trim().lowercase() == SPOILER_COLOR_NAME

private fun parseHexColor(hex: String): Color {
  val digits = hex.substring(1)
  return when (digits.length) {
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
    else -> Color(
      red = digits.substring(0, 2).toInt(16),
      green = digits.substring(2, 4).toInt(16),
      blue = digits.substring(4, 6).toInt(16),
      alpha = digits.substring(6, 8).toInt(16),
    )
  }
}

private fun Char.hexDigit(): Int = Character.digit(this, 16)

private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 2.5f

private val SIZE_VALUE = Regex("""^(\d+(?:\.\d+)?)%?$""")

fun resolveBBSizeScale(value: String): Float? {
  val match = SIZE_VALUE.matchEntire(value.trim()) ?: return null
  val scale = (match.groupValues[1].toFloatOrNull() ?: return null) / 100f
  if (!scale.isFinite() || scale <= 0f) return null
  return scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
