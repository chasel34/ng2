package com.chasel.ng2n.core.bbcode

internal fun normalize(nodes: List<ParseNode>): List<BBCodeNode> {
  val flattened = ArrayList<BBCodeNode>(nodes.size)
  flattenInto(nodes, flattened)

  val merged = ArrayList<BBCodeNode>(flattened.size)
  for (node in flattened) {
    val previous = merged.lastOrNull()
    if (node is TextNode && previous is TextNode) {
      merged[merged.size - 1] = TextNode(previous.value + node.value)
      continue
    }
    merged.add(if (node is ChildBearing) node.withChildren(normalize(node.children)) else node)
  }
  return merged
}

private fun flattenInto(nodes: List<ParseNode>, out: MutableList<BBCodeNode>) {
  for (node in nodes) {
    when (node) {
      is InternalNode -> flattenInto(node.children, out)
      is BBCodeNode -> out.add(node)
    }
  }
}

internal fun plainText(nodes: List<BBCodeNode>): String {
  val out = StringBuilder()
  appendPlainText(nodes, out)
  return out.toString()
}

private fun appendPlainText(nodes: List<BBCodeNode>, out: StringBuilder) {
  for (node in nodes) {
    when (node) {
      is TextNode -> out.append(node.value)
      is ChildBearing -> appendPlainText(node.children, out)
      else -> Unit
    }
  }
}
