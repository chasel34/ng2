package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger

/**
 * 清洗 / 信封 / 字段取值这三处是**逐行直译 JS**的,而 JS 有几处语义与 Kotlin 标准库不同,
 * 差一点就会在金样本上假红(或者更糟:线上静默取错值)。这里把它们抠成显式的小函数,
 * 用到的地方一律走这些,不用 Kotlin 的同名 API:
 *
 * 1. `\s` / `String.prototype.trim()` 的空白集合比 `Char.isWhitespace()` **多** `﻿`(BOM),
 *    见 [isJsWhitespace];
 * 2. `Number(str)` 的转换规则(空串 = 0、认 `0x`/`0o`/`0b`、其余必须是十进制字面量,
 *    **不认** Java 的 `1d` / `0x1p3` 这类写法),见 [jsNumber];
 * 3. **JS 对象的属性枚举序**:数组下标样式的键先按数值升序,其余键才按插入序
 *    (`Object.entries({b:2,'1':'x',a:1,'0':'y'})` → `0,1,b,a`),见 [jsOwnEntries]。
 *    `orderedEntries` 与 `extractServerError` 的多条错误说明拼接都吃这个序。
 *
 * 本文件不做别的事,也**不导出**:它是直译的脚手架,不是 app 的公共 API。
 */

/**
 * JS 正则 `\s` 与 `String.prototype.trim()` 的空白集合。
 *
 * 与 Kotlin `Char.isWhitespace()` 的差:`﻿`(零宽不换行空格 / BOM)在 JS 里算空白,
 * 在 JVM 里不算——清洗管线最后一步 `trim()` 正好可能撞上它(响应体剥 BOM 是解码层的事,
 * 但 `lite=htmljs` 那种把 JSON 夹在 HTML 里的响应,BOM 可能落在中间)。
 */
internal fun isJsWhitespace(ch: Char): Boolean = when (ch) {
  '\u0009', '\u000a', '\u000b', '\u000c', '\u000d', '\u0020',
  '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff',
  -> true
  else -> ch in '\u2000'..'\u200a'
}

/** `String.prototype.trim()`。 */
internal fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && isJsWhitespace(this[start])) start++
  while (end > start && isJsWhitespace(this[end - 1])) end--
  return substring(start, end)
}

/** `String.prototype.trimEnd()`。 */
internal fun String.jsTrimEnd(): String {
  var end = length
  while (end > 0 && isJsWhitespace(this[end - 1])) end--
  return substring(0, end)
}

/** JS 的十进制字面量。`1d` / `1f` / `0x1p3` 这些 `Double.parseDouble` 认、JS 不认的写法在这里被挡掉。 */
private val JS_DECIMAL_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$""")

/**
 * `Number(value)`。转不出来是 `NaN`(不是抛错,也不是 null)——调用方按 `isFinite` 判。
 *
 * 关键差异:**空串与纯空白串是 `0` 而不是 NaN**(`int(record, key)` 在字段值是 `"   "` 时
 * 因此返回 0 而不是「没这个值」)。这条看着像 bug,但它是 RN 版的现行行为,照抄。
 */
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

/** `Math.trunc` 之后落到 `Long`。调用方保证 [value] 有限。 */
internal fun jsTrunc(value: Double): Long = value.toLong()

/**
 * JS 的「数组下标样式的键」:规范的十进制、无前导零、≤ 2^32-2。
 * `"01"`、`"1e3"`、`" 1"`、`"-1"` 都**不是**,它们按普通字符串键处理。
 */
private fun arrayIndexOrNull(key: String): Long? {
  if (key.isEmpty() || key.length > 10) return null
  if (key == "0") return 0L
  if (key[0] == '0') return null
  for (ch in key) if (ch !in '0'..'9') return null
  val value = key.toLongOrNull() ?: return null
  return if (value <= 4294967294L) value else null
}

/**
 * `Object.entries(obj)` 的枚举序:数组下标样式的键先按数值升序,其余键按插入序跟在后面。
 *
 * kotlinx 的 [JsonObject] 保的是**文档序**,两者只在「文档里数字键不在最前」时才不同,
 * 而 NGA 的 `data` 里这种形态是常态(`{"__T":{…},"0":…}`)。不补这一下,
 * `orderedEntries` 的非数字键相对顺序与 TS 就会分家。
 */
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
