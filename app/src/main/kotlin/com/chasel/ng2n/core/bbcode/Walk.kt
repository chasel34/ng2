package com.chasel.ng2n.core.bbcode

/**
 * 遍历 AST 时「一个节点的子节点在哪」的唯一答案。直译 `src/core/bbcode/walk.ts`。
 *
 * 大多数容器节点把子节点放在 `children`,但 [ListNode] 放在 `items`、[TableNode] 放在
 * `rows[].cells[].children` —— 每个想递归走一遍 AST 的地方(切段、骰子复算、引用索引、
 * 统计)都得把这三种形状写一遍,加一种容器节点就得改所有地方。所以形状只在这里知道。
 *
 * 顺序照 TS:先看有没有 `children`,再看 list / table;三者互斥,谁在前不影响结果。
 */
fun childNodeLists(node: BBCodeNode): List<List<BBCodeNode>> = when (node) {
  is ChildBearing -> listOf(node.children)
  is ListNode -> node.items
  is TableNode -> node.rows.flatMap { row -> row.cells.map { it.children } }
  else -> emptyList()
}
