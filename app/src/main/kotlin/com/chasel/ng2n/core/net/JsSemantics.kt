package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger

internal fun isJsWhitespace(ch: Char): Boolean = when (ch) {
  '\u0009', '\u000a', '\u000b', '\u000c', '\u000d', '\u0020',
  '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff',
  -> true
  else -> ch in '\u2000'..'\u200a'
}

internal fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && isJsWhitespace(this[start])) start++
  while (end > start && isJsWhitespace(this[end - 1])) end--
  return substring(start, end)
}

internal fun String.jsTrimEnd(): String {
  var end = length
  while (end > 0 && isJsWhitespace(this[end - 1])) end--
  return substring(0, end)
}

private val JS_DECIMAL_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

internal fun jsNumber(raw: String): Double {
  val text = raw.jsTrim()
  if (text.isEmpty()) return 0.0
  when (text) {
    "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
    "-Infinity" -> return Double.NEGATIVE_INFINITY
  }
  if (text.length > 2 && text[0] == '0') {
    val radix = when (text[1]) {
      'x', 'X' -> 16
      'o', 'O' -> 8
      'b', 'B' -> 2
      else -> 0
    }
    if (radix != 0) {
      val digits = text.substring(2)
      return runCatching { BigInteger(digits, radix).toDouble() }.getOrElse { Double.NaN }
    }
  }
  return if (JS_DECIMAL_LITERAL.matches(text)) text.toDouble() else Double.NaN
}

internal fun jsTrunc(value: Double): Long = value.toLong()

private fun arrayIndexOrNull(key: String): Long? {
  if (key.isEmpty() || key.length > 10) return null
  if (key == "0") return 0L
  if (key[0] == '0') return null
  for (ch in key) if (ch !in '0'..'9') return null
  val value = key.toLongOrNull() ?: return null
  return if (value <= 4294967294L) value else null
}

internal fun jsOwnEntries(obj: JsonObject): List<Pair<String, JsonElement>> {
  val indexed = ArrayList<Pair<Long, Pair<String, JsonElement>>>(obj.size)
  val rest = ArrayList<Pair<String, JsonElement>>(obj.size)
  for ((key, value) in obj) {
    val index = arrayIndexOrNull(key)
    if (index == null) rest += key to value else indexed += index to (key to value)
  }
  indexed.sortBy { it.first }
  return indexed.map { it.second } + rest
}
