package com.chasel.ng2n.core.local

internal fun Char.isJsWhitespace(): Boolean = when (code) {
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20 -> true
  0x00a0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff -> true
  in 0x2000..0x200a -> true
  else -> false
}

internal fun String.jsTrim(): String = trim { it.isJsWhitespace() }

internal fun String.jsTrimStart(): String = trimStart { it.isJsWhitespace() }

internal val JS_WHITESPACE_RUN =
  Regex("[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

private val JS_DECIMAL_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

private val JS_RADIX_LITERAL = Regex("""^0[xXoObB][0-9a-fA-F]+$""")

internal fun jsNumber(text: String): Double {
  val trimmed = text.jsTrim()
  if (trimmed.isEmpty()) return 0.0
  return when {
    trimmed == "Infinity" || trimmed == "+Infinity" -> Double.POSITIVE_INFINITY
    trimmed == "-Infinity" -> Double.NEGATIVE_INFINITY
    JS_DECIMAL_LITERAL.matches(trimmed) -> trimmed.toDoubleOrNull() ?: Double.NaN
    JS_RADIX_LITERAL.matches(trimmed) -> {
      val radix = when (trimmed[1]) {
        'x', 'X' -> 16
        'o', 'O' -> 8
        else -> 2
      }
      trimmed.substring(2).toLongOrNull(radix)?.toDouble() ?: Double.NaN
    }
    else -> Double.NaN
  }
}
