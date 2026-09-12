package com.chasel.ng2n.core.bbcode

internal const val JS_SPACE_CLASS =
  "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

internal fun isJsSpace(char: Char): Boolean = when (char) {
  '\t', '\n', '\u000B', '\u000C', '\r', ' ',
  '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
  -> true
  else -> char in '\u2000'..'\u200A'
}

internal fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && isJsSpace(this[start])) start++
  while (end > start && isJsSpace(this[end - 1])) end--
  return if (start == 0 && end == length) this else substring(start, end)
}

internal fun String.isJsBlank(): Boolean {
  for (index in indices) if (!isJsSpace(this[index])) return false
  return true
}

internal fun jsParseInt(text: String?): Int? {
  val input = text ?: return null
  var index = 0
  while (index < input.length && isJsSpace(input[index])) index++
  var negative = false
  if (index < input.length && (input[index] == '+' || input[index] == '-')) {
    negative = input[index] == '-'
    index++
  }
  val start = index
  var value = 0L
  while (index < input.length && input[index] in '0'..'9') {
    if (value <= Int.MAX_VALUE) value = value * 10 + (input[index] - '0')
    index++
  }
  if (index == start) return null
  val clamped = if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
  return if (negative) -clamped else clamped
}

internal fun String.asciiLowercase(): String {
  var index = 0
  while (index < length && this[index] !in 'A'..'Z') index++
  if (index == length) return this
  val out = StringBuilder(this)
  while (index < length) {
    val char = out[index]
    if (char in 'A'..'Z') out[index] = char + 32
    index++
  }
  return out.toString()
}
