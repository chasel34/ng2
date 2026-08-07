import { hasChildren, isInternalNode, type InternalNode } from './internal'
import type { BBCodeNode } from './types'

/**
 * 合并相邻文本,并摊平任何没被父标签收走的中间节点。
 *
 * 相邻文本是解析过程的常态:未知标签透传成文本、未闭合标签降级成文本,
 * 都会在已有文本旁边再落一段。渲染层不该关心这些,统一在这里收干净。
 */
export function normalize(nodes: readonly InternalNode[]): BBCodeNode[] {
  const flattened: InternalNode[] = []
  for (const node of nodes) {
    if (isInternalNode(node)) {
      flattened.push(...node.children)
      continue
    }
    flattened.push(node)
  }

  const merged: BBCodeNode[] = []
  for (const node of flattened as BBCodeNode[]) {
    const previous = merged[merged.length - 1]
    if (node.type === 'text' && previous?.type === 'text') {
      merged[merged.length - 1] = { type: 'text', value: previous.value + node.value }
      continue
    }
    merged.push(hasChildren(node) ? { ...node, children: normalize(node.children) } : node)
  }
  return merged
}

/** 取子树里的可见文字,用于只需要一个字符串的节点(如 `[@]名字[/@]`)。 */
export function plainText(nodes: readonly BBCodeNode[]): string {
  return nodes
    .map((node) => {
      if (node.type === 'text') return node.value
      return hasChildren(node) ? plainText(node.children as readonly BBCodeNode[]) : ''
    })
    .join('')
}
