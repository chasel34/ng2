package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ChildBearing
import com.chasel.ng2n.core.bbcode.LineBreakNode
import com.chasel.ng2n.core.bbcode.QuoteNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.parseBBCode

fun plainTextOf(content: String): String = flattenPlainText(parseBBCode(content))
  .replace(WHITESPACE, " ")
  .trim()

/** 楼层自己说的话：跳过引用块，整层只有引用时退回全文。 */
fun ownTextOf(content: String): String {
  val nodes = parseBBCode(content)
  val own = flattenPlainText(nodes, skipQuotes = true).replace(WHITESPACE, " ").trim()
  return own.ifEmpty { flattenPlainText(nodes).replace(WHITESPACE, " ").trim() }
}

private val WHITESPACE = Regex("""\s+""")

private fun flattenPlainText(nodes: List<BBCodeNode>, skipQuotes: Boolean = false): String = buildString {
  for (node in nodes) {
    when (node) {
      is TextNode -> append(node.value)
      LineBreakNode -> append(' ')
      is QuoteNode -> if (!skipQuotes) append(flattenPlainText(node.children))
      is ChildBearing -> append(flattenPlainText(node.children, skipQuotes))
      else -> Unit
    }
  }
}
