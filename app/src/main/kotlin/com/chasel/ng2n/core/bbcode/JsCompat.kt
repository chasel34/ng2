package com.chasel.ng2n.core.bbcode

/**
 * 直译 TS 时必须逐字对上的三处 JS 语义。**不是「差不多就行」的工具函数**——
 * 换成 Kotlin/Java 的同名 API 会在真实楼层上产生可见差异:
 *
 * 1. **`\s` 的字符集**。JS 正则的 `\s` 含 NBSP(` `)、全角空格(`　`)、BOM
 *    (`﻿`)等一大票;Java 正则的 `\s` 只有 `[ \t\n\x0B\f\r]` 六个 ASCII。
 *    NGA 正文里 `&nbsp;` 解码出来的 NBSP 遍地都是,`[td width=1 ]`、`[dice 2d6]`
 *    这类形态就靠 `\s` 断句。
 * 2. **`String.prototype.trim()`**。同样含 NBSP / 全角空格 / BOM;而 Kotlin 的
 *    `String.trim()` 走 `Character.isWhitespace`,**恰恰把 NBSP 排除在外**
 *    (它是「非断行空格」)。`[img] 地址 [/img]` 里如果是 NBSP,两边结果就不一样。
 * 3. **`Number.parseInt(s, 10)`**。JS 是「跳空白 → 可选正负号 → 吃前缀数字」,
 *    `"2abc"` → `2`、`"abc"` → `NaN`;Kotlin 的 `toIntOrNull()` 要求整串是数字。
 *    `[td colspan=2px]` 这种脏属性在站上是有的。
 */

/** JS 正则 `\s` 的字符集,写成正则字符类的**内容**(不含方括号)。 */
internal const val JS_SPACE_CLASS =
  "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** 与 [JS_SPACE_CLASS] 同集合的逐字符判定(`trim` 用)。 */
internal fun isJsSpace(char: Char): Boolean = when (char) {
  '\t', '\n', '\u000B', '\u000C', '\r', ' ',
  '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
  -> true
  // U+2000–U+200A:各种定宽空格
  else -> char in '\u2000'..'\u200A'
}

/** `String.prototype.trim()`。 */
internal fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && isJsSpace(this[start])) start++
  while (end > start && isJsSpace(this[end - 1])) end--
  return if (start == 0 && end == length) this else substring(start, end)
}

/** `trim().length === 0`,但不真的切出子串。 */
internal fun String.isJsBlank(): Boolean {
  for (index in indices) if (!isJsSpace(this[index])) return false
  return true
}

/**
 * `Number.parseInt(text, 10)`,`NaN` 落成 `null`。
 *
 * 溢出口径与 JS 有意不同:JS 会得到一个巨大但有限的浮点数(`parseInt("1e30 位数字")`
 * 照样 `> 0`),这里饱和到 [Int.MAX_VALUE]。落点只有 `colspan`/`rowspan`,
 * 两边都只会得到「一个大得没意义的正数」,渲染结果一致。
 */
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

/**
 * 只小写 ASCII 字母,长度**逐码元不变**。
 *
 * 解析器要拿「全文小写副本」按下标找闭标签(`lowerSource.indexOf("[/img]", from)`),
 * 而 `String.toLowerCase()` 在 JS 与 Java 上都会让个别字符变长(`İ` U+0130 →
 * `i` + U+0307 两个码元),一旦变长,小写副本的下标就和原文对不上,截出来的
 * `[code]` 内容会整体偏移。标签名按正则只可能是 `[a-zA-Z0-9_]`,所以只小写 ASCII
 * 既够用又不会错位。**这是对 RN 版的有意修正**(RN 版有同一个隐患,未触发过)。
 */
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
