package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.bbcode.unescapeNgaText

private const val JS_WS = "\\u0009\\u000a\\u000b\\u000c\\u000d\\u0020" +
  "\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"

private val BR_TAG = Regex("<br[$JS_WS]*/?>", RegexOption.IGNORE_CASE)

private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")

private val INLINE_SPACES = Regex("[${JS_WS.replace("\\u000a", "")}]+")

private val BLANK_LINES = Regex("\n{3,}")

fun stripServerHtml(raw: String): String {
  val text = unescapeNgaText(HTML_TAG.replace(BR_TAG.replace(raw, "\n"), ""))
  return BLANK_LINES.replace(
    text.split("\n").joinToString("\n") { line -> INLINE_SPACES.replace(line, " ").jsTrim() },
    "\n\n",
  ).jsTrim()
}
