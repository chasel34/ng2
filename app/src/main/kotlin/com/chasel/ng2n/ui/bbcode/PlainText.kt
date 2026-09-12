package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ChildBearing
import com.chasel.ng2n.core.bbcode.LineBreakNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.parseBBCode

fun plainTextOf(content: String): String = flattenPlainText(parseBBCode(content))
  .replace(WHITESPACE, " ")
  .trim()

private val WHITESPACE = Regex("""\s+""")

private fun flattenPlainText(nodes: List<BBCodeNode>): String = buildString {
  for (node in nodes) {
    when (node) {
      is TextNode -> append(node.value)
      LineBreakNode -> append(' ')
      is ChildBearing -> append(flattenPlainText(node.children))
      else -> Unit
    }
  }
}
