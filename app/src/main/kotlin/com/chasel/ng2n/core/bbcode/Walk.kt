package com.chasel.ng2n.core.bbcode

fun childNodeLists(node: BBCodeNode): List<List<BBCodeNode>> = when (node) {
  is ChildBearing -> listOf(node.children)
  is ListNode -> node.items
  is TableNode -> node.rows.flatMap { row -> row.cells.map { it.children } }
  else -> emptyList()
}
