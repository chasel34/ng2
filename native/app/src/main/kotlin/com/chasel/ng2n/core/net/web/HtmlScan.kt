package com.chasel.ng2n.core.net.web

import com.chasel.ng2n.core.net.jsTrim

/**
 * 从一页 HTML 里抠东西的通用扫描器(Web 反解档专用,ADR-0002)。
 * 直译 `src/core/net/web/html-scan.ts`。
 *
 * 不引 DOM 也不引 HTML 解析库:core 层零依赖,而反解只需要「按 id 取一段 innerHTML」
 * 与「把一串 JS 实参切开」两件事。写成带字符串状态的扫描而不是正则,是因为
 * 要抠的正是**用户内容**——正文里有 `'`、`"`、`{`、`)`、`</span>` 全是常事,
 * 裸正则会在别人贴一段代码时把整页反解带偏。
 */

/** 一段 JS 实参:数字/字符串/null,或 `$('id')` 这种拿不到值的表达式。 */
sealed interface JsArgument {

  @JvmInline
  value class Str(val value: String) : JsArgument

  /**
   * JS 只有一种 number(IEEE754 双精度),这里照收 [Double]。
   * 落到信封里时由 [jsonNumber] 按 `JSON.stringify` 的口径决定写成整数还是小数。
   */
  @JvmInline
  value class Num(val value: Double) : JsArgument

  data object Null : JsArgument

  /** 其它表达式,原文照留(`$('postcontent0')` 靠它取到里面那个 id) */
  @JvmInline
  value class Expression(val text: String) : JsArgument
}

/** 一处 `<callee>( … )` 调用:出现位置、切好的实参、右括号之后的位置。 */
class JsCall(val at: Int, val args: List<JsArgument>, val end: Int)

/** [balancedSlice] 的结果:**不含**两端括号的内容 + 闭合括号之后的位置。 */
class BalancedSlice(val body: String, val end: Int)

/**
 * 从 [from] 起找 [open] / [close] 配对的那一段,返回**不含**两端括号的内容。
 * 括号计数时跳过字符串字面量(单双引号都算)与其中的转义。找不到配对返回 null。
 */
fun balancedSlice(text: String, from: Int, open: Char, close: Char): BalancedSlice? {
  val start = text.indexOf(open, from)
  if (start < 0) return null

  var depth = 0
  var quote: Char? = null
  var i = start
  while (i < text.length) {
    val char = text[i]
    if (quote != null) {
      if (char == '\\') i += 1 else if (char == quote) quote = null
      i += 1
      continue
    }
    if (char == '\'' || char == '"') {
      quote = char
      i += 1
      continue
    }
    if (char == open) {
      depth += 1
    } else if (char == close) {
      depth -= 1
      if (depth == 0) return BalancedSlice(text.substring(start + 1, i), i + 1)
    }
    i += 1
  }
  return null
}

/** 按顶层逗号切开实参串(字符串里的、嵌套括号里的逗号不算)。 */
private fun splitTopLevel(body: String): List<String> {
  val parts = ArrayList<String>()
  var depth = 0
  var quote: Char? = null
  var start = 0
  var i = 0
  while (i < body.length) {
    val char = body[i]
    if (quote != null) {
      if (char == '\\') i += 1 else if (char == quote) quote = null
      i += 1
      continue
    }
    when {
      char == '\'' || char == '"' -> quote = char
      char == '(' || char == '[' || char == '{' -> depth += 1
      char == ')' || char == ']' || char == '}' -> depth -= 1
      char == ',' && depth == 0 -> {
        parts += body.substring(start, i)
        start = i + 1
      }
    }
    i += 1
  }
  parts += body.substring(start)
  return parts
}

/**
 * JS 字符串字面量 → 值。只还原 `\'` `\"` `\\` `\n` `\t` `\r`,NGA 的实参里只有这些。
 *
 * 首字符不是引号时**原样返回**(TS 版最后那个三元的语义),调用方靠这条给
 * `parseObjectLiterals` 里「拼出来的伪字面量」兜底。
 */
private fun unquote(literal: String): String {
  val quote = literal.firstOrNull()
  // TS 的 `literal.slice(1, -1)`:长度不足 2 时得到空串,不越界
  val inner = if (literal.length >= 2) literal.substring(1, literal.length - 1) else ""
  val out = StringBuilder(inner.length)
  var i = 0
  while (i < inner.length) {
    val char = inner[i]
    if (char != '\\') {
      out.append(char)
      i += 1
      continue
    }
    i += 1
    if (i >= inner.length) break
    out.append(
      when (val next = inner[i]) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        else -> next
      },
    )
    i += 1
  }
  return if (quote == '"' || quote == '\'') out.toString() else literal
}

/** `^[+-]?\d+(\.\d+)?$` —— TS 版 `parseArgument` 认数字的那条判据。 */
private val NUMBER_LITERAL = Regex("""^[+-]?\d+(\.\d+)?$""")

private fun parseArgument(raw: String): JsArgument {
  val text = raw.jsTrim()
  if (text == "null" || text == "undefined" || text.isEmpty()) return JsArgument.Null
  if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
    return JsArgument.Str(unquote(text))
  }
  if (NUMBER_LITERAL.matches(text)) return JsArgument.Num(text.toDouble())
  return JsArgument.Expression(text)
}

/**
 * 找一处 `<callee>( … )` 调用并切开它的实参。[callee] 带上结尾那个 `(`
 * (`commonui.postArg.proc(`),免得匹配到同前缀的别的函数。
 * [from] 之后第一处;返回 [JsCall.end] 便于连续找下一处。
 */
fun findCall(html: String, callee: String, from: Int = 0): JsCall? {
  val at = html.indexOf(callee, from)
  if (at < 0) return null
  // 从 callee 自带的那个 `(` 上开始配对,而不是它后面——否则会跳到实参里的括号去
  val slice = balancedSlice(html, at + callee.length - 1, '(', ')') ?: return null
  return JsCall(at, splitTopLevel(slice.body).map(::parseArgument), slice.end)
}

/** 同 [findCall],把这一页里所有调用都找出来(按出现顺序)。 */
fun findCalls(html: String, callee: String): List<JsCall> {
  val calls = ArrayList<JsCall>()
  var cursor = 0
  while (true) {
    val call = findCall(html, callee, cursor) ?: return calls
    calls += call
    cursor = call.end
  }
}

/** `$('postcontent0')` → `postcontent0`;不是这个形状(含 `null`)返回 null。 */
private val ELEMENT_ID = Regex("""^\$\(\s*['"]([^'"]+)['"]\s*\)$""")

fun elementIdOf(argument: JsArgument?): String? {
  if (argument !is JsArgument.Expression) return null
  return ELEMENT_ID.find(argument.text)?.groupValues?.get(1)
}

/** `id='<id>'` / `id="<id>"`,反向引用保证两端引号一致。 */
private val ID_ATTRIBUTE = Regex("""\sid\s*=\s*(['"])([^'"]*)\1""")

/** 找 `id='<id>'` 所在标签的起始 `<` 位置。 */
private fun findTagWithId(html: String, id: String): Int? {
  for (match in ID_ATTRIBUTE.findAll(html)) {
    if (match.groupValues[2] != id) continue
    val open = html.lastIndexOf('<', match.range.first)
    if (open >= 0) return open
  }
  return null
}

private val TAG_NAME = Regex("""^<([a-zA-Z][\w-]*)""")

/**
 * 取 [id] 那个元素的 innerHTML(原样,不做实体解码)。
 *
 * **不解码是对的**:NGA 网页版里 `postcontent` 的 innerHTML 与 JSON 接口的
 * `content` 字段逐字节相同——`&amp;` 与 `<br/>` 两边都留着,下游 BBCode 解析器
 * 本来就按这个口径吃。多解一轮反而会把正文里的 `&amp;lt;` 解坏。
 */
fun innerHtmlOf(html: String, id: String): String? {
  val open = findTagWithId(html, id) ?: return null
  val tagName = TAG_NAME.find(html.substring(open, minOf(html.length, open + 32)))
    ?.groupValues?.get(1)
    ?: return null

  val contentStart = html.indexOf('>', open)
  if (contentStart < 0) return null
  if (contentStart > 0 && html[contentStart - 1] == '/') return ""

  // 同名标签可以嵌套(正文外面那层 span 里还可能有 span),按深度找收尾的那个
  val lower = html.lowercase()
  val openTag = "<" + tagName.lowercase()
  val closeTag = "</" + tagName.lowercase()
  var depth = 1
  var cursor = contentStart + 1
  while (depth > 0) {
    val nextOpen = lower.indexOf(openTag, cursor)
    val nextClose = lower.indexOf(closeTag, cursor)
    if (nextClose < 0) return null
    if (nextOpen >= 0 && nextOpen < nextClose) {
      val tagEnd = html.indexOf('>', nextOpen)
      if (tagEnd < 0) return null
      // `<br/>` 这种自闭合的不增加深度
      if (html[tagEnd - 1] != '/') depth += 1
      cursor = tagEnd + 1
      continue
    }
    depth -= 1
    if (depth == 0) return html.substring(contentStart + 1, nextClose)
    cursor = nextClose + closeTag.length
  }
  return null
}

/** 取 `var x = '…'` / `x=parseInt('…')` 里那个整数。 */
fun readIntVariable(html: String, name: String): Long? {
  val raw = Regex("""$name\s*=\s*(?:parseInt\(\s*)?'?(-?\d+)'?""").find(html)?.groupValues?.get(1)
  return raw?.toLongOrNull()
}

/** 取 `x = 'value'` 里那个字符串。 */
fun readStringVariable(html: String, name: String): String? {
  val raw = Regex("""$name\s*=\s*'((?:[^'\\]|\\.)*)'""").find(html)?.groupValues?.get(1)
  return if (raw == null) null else unquote("'$raw'")
}

/** 取 `<!--<name>start-->…<!--<name>end-->` 之间的内容。 */
fun readMarkedSection(html: String, name: String): String? {
  val open = "<!--${name}start-->"
  val close = "<!--${name}end-->"
  val from = html.indexOf(open)
  if (from < 0) return null
  val to = html.indexOf(close, from + open.length)
  return if (to < 0) null else html.substring(from + open.length, to)
}

/**
 * `key:'value'` / `key:"value"` / `key:123`。键不带引号,所以不能走 JSON 解析。
 * 三个取值分支分开成组,好区分「匹配到空串」与「这一支根本没匹配」——
 * TS 那边 `match[2] ?? match[3] ?? match[4]` 靠的正是 `undefined` 与 `''` 的差别。
 */
private val OBJECT_FIELD =
  Regex("""([A-Za-z_]\w*)\s*:\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|(-?\d+))""")

/**
 * 解一串 JS 对象字面量(`[{aid:'',url:'…'},…]`)里每个对象的 `key:'value'` 对。
 * 键不带引号,所以不能走 JSON 解析;值一律当字符串收(下游 `int()` 会再转)。
 */
fun parseObjectLiterals(source: String): List<Map<String, String>> {
  val objects = ArrayList<Map<String, String>>()
  var cursor = 0
  while (true) {
    val slice = balancedSlice(source, cursor, '{', '}') ?: return objects
    val fields = LinkedHashMap<String, String>()
    for (match in OBJECT_FIELD.findAll(slice.body)) {
      val raw = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
      fields[match.groupValues[1]] = unquote("'$raw'")
    }
    objects += fields
    cursor = slice.end
  }
}
