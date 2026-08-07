import type { BBCodeNode } from './types'

/**
 * 遍历 AST 时「一个节点的子节点在哪」的唯一答案。
 *
 * 大多数容器节点把子节点放在 `children`，但 `list` 放在 `items`、`table` 放在
 * `rows[].cells[].children`——每个想递归走一遍 AST 的地方（切段、骰子复算、统计）
 * 都得把这三种形状写一遍，加一种容器节点就得改所有地方。所以形状只在这里知道。
 */
export function childNodeLists(node: BBCodeNode): readonly (readonly BBCodeNode[])[] {
  if ('children' in node) return [node.children]
  if (node.type === 'list') return node.items
  if (node.type === 'table') return node.rows.flatMap((row) => row.cells.map((cell) => cell.children))
  return []
}
