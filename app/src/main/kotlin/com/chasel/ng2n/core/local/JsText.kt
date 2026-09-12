package com.chasel.ng2n.core.local

/**
 * 几个「跟 JS 一模一样」的字符串小工具。
 *
 * 逆向算法这一层的判据是 RN 版跑出来的结果,而 **JVM 与 JS 对「空白」的定义并不一样**:
 * - Kotlin 的 `String.trim()` 走 `Character.isWhitespace`,它**不算** NBSP(U+00A0)与
 *   BOM(U+FEFF),却把 U+001C–U+001F 这些文件分隔符算成空白;
 * - Java 正则的 `\s` 默认只有 ASCII 六个。
 *
 * NGA 的用户名与标题里 NBSP、全角空格(U+3000)都常见,靠它保排版。所以这里按
 * ECMA-262 的 WhiteSpace + LineTerminator 表逐字符照抄,别换成 JDK 那套。
 */

/** ECMA-262 的 WhiteSpace + LineTerminator(`String.prototype.trim` 与正则 `\s` 认的那一套)。 */
internal fun Char.isJsWhitespace(): Boolean = when (code) {
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20 -> true
  0x00a0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff -> true
  in 0x2000..0x200a -> true
  else -> false
}

/** JS 的 `String.prototype.trim`。 */
internal fun String.jsTrim(): String = trim { it.isJsWhitespace() }

/** JS 的 `String.prototype.trimStart`。 */
internal fun String.jsTrimStart(): String = trimStart { it.isJsWhitespace() }

/** JS 正则里的 `\s+`。 */
internal val JS_WHITESPACE_RUN =
  Regex("[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

/** JS 数值字面量的十进制形状。 */
private val JS_DECIMAL_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

/** JS 数值字面量的进制前缀形状(`0x` / `0o` / `0b`,不带符号)。 */
private val JS_RADIX_LITERAL = Regex("""^0[xXoObB][0-9a-fA-F]+$""")

/**
 * JS 的 `Number(string)`,解不出来给 `NaN`。
 *
 * 与 Kotlin 的 `toDoubleOrNull` 差在两头:JDK 认 `1d` / `1f` / `0x1p3` 这些 Java 字面量
 * 而 JS 不认,JS 认 `0x10` / `0b101` / `Infinity` 而 JDK 不认;空串在 JS 里是 `0`。
 */
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
