import type { BBCodeNode } from './types'

/** 扫描到的一个开标签。 */
export interface OpenTag {
  /** 小写标签名。 */
  readonly name: string
  /** `[tag=value]` 里的 value。 */
  readonly value?: string
  /** `[td colspan=2 width=100]` 这类空格分隔的属性。 */
  readonly attrs?: Readonly<Record<string, string>>
  /** 空格之后的原始属性文本,给 `[dice 2d6]` 这种不是 kv 形式的用。 */
  readonly attrText?: string
  /** 原始开标签文本,标签未闭合时按原样降级成文本用。 */
  readonly raw: string
  /** 开标签在原文里占的字符数。 */
  readonly length: number
}

/**
 * 只在解析过程中存在的中间节点。`[*]`、`[tr]`、`[td]` 单独拿出来没有意义,
 * 它们由父级的 `[list]`/`[table]` 收集掉;`__fragment` 则是 `[stripbr]` 这种
 * 只加工内容、自己不留痕迹的标签。没被父级收走的,归一化时摊平成内容。
 */
export interface ListItemNode {
  readonly type: '__listitem'
  readonly children: readonly InternalNode[]
}

export interface TableRowNode {
  readonly type: '__tr'
  readonly children: readonly InternalNode[]
}

export interface TableCellNode {
  readonly type: '__td'
  readonly colspan: number
  readonly rowspan: number
  readonly width?: string
  readonly children: readonly InternalNode[]
}

export interface FragmentNode {
  readonly type: '__fragment'
  readonly children: readonly InternalNode[]
}

export type InternalNode =
  | BBCodeNode
  | ListItemNode
  | TableRowNode
  | TableCellNode
  | FragmentNode

const INTERNAL_TYPES: ReadonlySet<string> = new Set(['__listitem', '__tr', '__td', '__fragment'])

export function isInternalNode(
  node: InternalNode,
): node is ListItemNode | TableRowNode | TableCellNode | FragmentNode {
  return INTERNAL_TYPES.has(node.type)
}

/** 带子节点的节点。`list`/`table` 的内容藏在 `items`/`rows` 里,不算在内。 */
export function hasChildren(
  node: InternalNode,
): node is Extract<InternalNode, { children: readonly InternalNode[] }> {
  return 'children' in node
}
