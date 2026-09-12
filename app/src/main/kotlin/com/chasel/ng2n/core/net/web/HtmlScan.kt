package com.chasel.ng2n.core.net.web

import com.chasel.ng2n.core.net.jsTrim

sealed interface JsArgument {

  @JvmInline
  value class Str(val value: String) : JsArgument

  @JvmInline
  value class Num(val value: Double) : JsArgument

  data object Null : JsArgument

  @JvmInline
  value class Expression(val text: String) : JsArgument
}

class JsCall(val at: Int, val args: List<JsArgument>, val end: Int)

class BalancedSlice(val body: String, val end: Int)

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

private fun unquote(literal: String): String {
  val quote = literal.firstOrNull()
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

fun findCall(html: String, callee: String, from: Int = 0): JsCall? {
  val at = html.indexOf(callee, from)
  if (at < 0) return null
  val slice = balancedSlice(html, at + callee.length - 1, '(', ')') ?: return null
  return JsCall(at, splitTopLevel(slice.body).map(::parseArgument), slice.end)
}

fun findCalls(html: String, callee: String): List<JsCall> {
  val calls = ArrayList<JsCall>()
  var cursor = 0
  while (true) {
    val call = findCall(html, callee, cursor) ?: return calls
    calls += call
    cursor = call.end
  }
}

private val ELEMENT_ID = Regex("""^\$\(\s*['"]([^'"]+)['"]\s*\)$""")

fun elementIdOf(argument: JsArgument?): String? {
  if (argument !is JsArgument.Expression) return null
  return ELEMENT_ID.find(argument.text)?.groupValues?.get(1)
}

private val ID_ATTRIBUTE = Regex("""\sid\s*=\s*(['"])([^'"]*)\1""")

private fun findTagWithId(html: String, id: String): Int? {
  for (match in ID_ATTRIBUTE.findAll(html)) {
    if (match.groupValues[2] != id) continue
    val open = html.lastIndexOf('<', match.range.first)
    if (open >= 0) return open
  }
  return null
}

private val TAG_NAME = Regex("""^<([a-zA-Z][\w-]*)""")

fun innerHtmlOf(html: String, id: String): String? {
  val open = findTagWithId(html, id) ?: return null
  val tagName = TAG_NAME.find(html.substring(open, minOf(html.length, open + 32)))
    ?.groupValues?.get(1)
    ?: return null

  val contentStart = html.indexOf('>', open)
  if (contentStart < 0) return null
  if (contentStart > 0 && html[contentStart - 1] == '/') return ""

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

fun readIntVariable(html: String, name: String): Long? {
  val raw = Regex("""$name\s*=\s*(?:parseInt\(\s*)?'?(-?\d+)'?""").find(html)?.groupValues?.get(1)
  return raw?.toLongOrNull()
}

fun readStringVariable(html: String, name: String): String? {
  val raw = Regex("""$name\s*=\s*'((?:[^'\\]|\\.)*)'""").find(html)?.groupValues?.get(1)
  return if (raw == null) null else unquote("'$raw'")
}

fun readMarkedSection(html: String, name: String): String? {
  val open = "<!--${name}start-->"
  val close = "<!--${name}end-->"
  val from = html.indexOf(open)
  if (from < 0) return null
  val to = html.indexOf(close, from + open.length)
  return if (to < 0) null else html.substring(from + open.length, to)
}

private val OBJECT_FIELD =
  Regex("""([A-Za-z_]\w*)\s*:\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|(-?\d+))""")

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
