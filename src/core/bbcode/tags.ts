import type { InternalNode, OpenTag, TableCellNode, TableRowNode } from './internal'
import { normalize, plainText } from './normalize'
import type { AttachmentRef, BBCodeNode, TableCell, TableRow } from './types'

/**
 * 标签清单 = 功能文档 §2.9 的两边并集。加一个新标签只需要动这个文件:
 * 内容要当正文解析的进 `CONTAINER_BUILDERS`,内容是原文的进 `RAW_BUILDERS`。
 */

/** 把 frame 的内容折成一个 AST 节点(或只在解析期存在的中间节点)。 */
type ContainerBuilder = (open: OpenTag, children: readonly InternalNode[]) => InternalNode

export const CONTAINER_BUILDERS: Readonly<Record<string, ContainerBuilder>> = {
  b: (_open, children) => ({ type: 'bold', children: normalize(children) }),
  i: (_open, children) => ({ type: 'italic', children: normalize(children) }),
  u: (_open, children) => ({ type: 'underline', children: normalize(children) }),
  del: (_open, children) => ({ type: 'strike', children: normalize(children) }),
  color: (open, children) => ({
    type: 'color',
    value: open.value ?? '',
    children: normalize(children),
  }),
  size: (open, children) => ({
    type: 'size',
    value: open.value ?? '',
    children: normalize(children),
  }),
  font: (open, children) => ({
    type: 'font',
    value: open.value ?? '',
    children: normalize(children),
  }),

  quote: (_open, children) => ({ type: 'quote', children: normalize(children) }),
  collapse: (open, children) =>
    open.value === undefined || open.value.length === 0
      ? { type: 'collapse', children: normalize(children) }
      : { type: 'collapse', title: open.value, children: normalize(children) },

  align: (open, children) => ({
    type: 'align',
    align: toAlign(open.value),
    children: normalize(children),
  }),
  l: (_open, children) => ({ type: 'align', align: 'left', children: normalize(children) }),
  r: (_open, children) => ({ type: 'align', align: 'right', children: normalize(children) }),
  h: (_open, children) => ({ type: 'heading', children: normalize(children) }),

  list: (open, children) => ({
    type: 'list',
    ordered: open.value !== undefined && open.value.length > 0,
    items: children
      .filter((child) => child.type === '__listitem')
      .map((item) => normalize(trimEdges(item.children))),
  }),
  '*': (_open, children) => ({ type: '__listitem', children }),

  table: (_open, children) => ({
    type: 'table',
    rows: children.filter((child): child is TableRowNode => child.type === '__tr').map(toTableRow),
  }),
  tr: (_open, children) => ({ type: '__tr', children }),
  td: (open, children) => ({
    type: '__td',
    colspan: toSpan(open.attrs?.colspan),
    rowspan: toSpan(open.attrs?.rowspan),
    ...(open.attrs?.width === undefined ? {} : { width: open.attrs.width }),
    children,
  }),

  url: (open, children) => ({ type: 'link', href: open.value ?? '', children: normalize(children) }),
  uid: (open, children) => ({
    type: 'userRef',
    uid: open.value ?? '',
    children: normalize(children),
  }),
  tid: (open, children) => ({
    type: 'topicRef',
    tid: open.value ?? '',
    children: normalize(children),
  }),
  pid: (open, children) => {
    const args = (open.value ?? '').split(',')
    return { type: 'floorRef', pid: args[0] ?? '', args, children: normalize(children) }
  },
  '@': (_open, children) => ({ type: 'mention', username: plainText(normalize(children)) }),

  lessernuke: nukeBox('post'),
  // 官方把处罚种类写在标签名末尾那一位数字上,`[lessernuke]` 与 `[lessernuke1]` 等价
  lessernuke1: nukeBox('post'),
  lessernuke2: nukeBox('topic'),
  lessernuke3: nukeBox('locked'),
  hip: (_open, children) => ({ type: 'box', variant: 'hip', children: normalize(children) }),
  item: (_open, children) => ({ type: 'box', variant: 'item', children: normalize(children) }),
  stripbr: (_open, children) => ({
    type: '__fragment',
    children: children.filter((child) => child.type !== 'linebreak'),
  }),
}

/** 把标签内的原文折成节点。用于内容不解析标签的那些标签。 */
type RawBuilder = (open: OpenTag, value: string) => BBCodeNode

export const RAW_BUILDERS: Readonly<Record<string, RawBuilder>> = {
  code: (_open, value) => ({ type: 'code', value }),
  img: (_open, value) => ({ type: 'image', variant: 'img', ...toAttachmentRef(value) }),
  noimg: (_open, value) => ({ type: 'image', variant: 'noimg', ...toAttachmentRef(value) }),
  attach: (_open, value) => ({ type: 'attach', ...toAttachmentRef(value) }),
  album: (_open, value) => ({ type: 'album', value: value.trim() }),
  flash: (open, value) => ({
    type: 'flash',
    media: toMedia(open.value),
    ...toAttachmentRef(value),
  }),
  dice: (_open, value) => ({ type: 'dice', expression: value.trim() }),
  url: (_open, value) => ({ type: 'link', href: value.trim(), children: [] }),
  uid: (_open, value) => ({ type: 'userRef', uid: value.trim(), children: [] }),
  tid: (_open, value) => ({ type: 'topicRef', tid: value.trim(), children: [] }),
  pid: (_open, value) => ({
    type: 'floorRef',
    pid: value.trim(),
    args: [value.trim()],
    children: [],
  }),
}

/**
 * 不带 `=` 参数时内容才是原文:`[url]地址[/url]` 的内容是地址,
 * 而 `[url=地址]文字[/url]` 的文字要当正文解析。其余 `RAW_BUILDERS` 里的标签内容永远是原文。
 */
const RAW_WHEN_BARE_TAGS: ReadonlySet<string> = new Set(['url', 'uid', 'tid', 'pid'])

export function isRawTag(open: OpenTag): boolean {
  if (RAW_BUILDERS[open.name] === undefined) return false
  return !RAW_WHEN_BARE_TAGS.has(open.name) || open.value === undefined
}

/**
 * 收尾时可以安全自闭合的结构性标签——它们只在父标签里有意义,
 * 缺闭标签属于 NGA 常态,不该像普通标签那样降级成文本。
 */
export const SELF_CLOSING_TAGS: ReadonlySet<string> = new Set(['*', 'tr', 'td'])

function nukeBox(punishment: 'post' | 'topic' | 'locked'): ContainerBuilder {
  return (_open, children) => ({
    type: 'box',
    variant: 'lessernuke',
    punishment,
    children: normalize(children),
  })
}

function toAlign(value: string | undefined): 'left' | 'center' | 'right' {
  return value === 'center' || value === 'right' ? value : 'left'
}

/** 带协议或以 `//` 开头的才是绝对地址;其余(含裸文件名)都要拼附件域名。 */
const ABSOLUTE_URL = /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|\/\/)/

function toAttachmentRef(value: string): AttachmentRef {
  const trimmed = value.trim()
  if (ABSOLUTE_URL.test(trimmed)) return { src: trimmed, needsAttachBase: false }
  const src = trimmed.startsWith('./') ? trimmed.slice(2) : trimmed
  return { src, needsAttachBase: src.length > 0 }
}

function toMedia(value: string | undefined): 'video' | 'audio' | 'flash' {
  return value === 'video' || value === 'audio' ? value : 'flash'
}

function toSpan(value: string | undefined): number {
  const span = Number.parseInt(value ?? '', 10)
  return Number.isFinite(span) && span > 0 ? span : 1
}

function toTableRow(row: TableRowNode): TableRow {
  const cells: TableCell[] = row.children
    .filter((child): child is TableCellNode => child.type === '__td')
    .map((cell) => ({
      colspan: cell.colspan,
      rowspan: cell.rowspan,
      ...(cell.width === undefined ? {} : { width: cell.width }),
      children: normalize(trimEdges(cell.children)),
    }))
  return { cells }
}

/** 丢掉首尾游离的换行与纯空白文本——NGA 的结构标签之间到处是这种缩进。 */
function trimEdges(nodes: readonly InternalNode[]): readonly InternalNode[] {
  let start = 0
  let end = nodes.length
  while (start < end && isBlank(nodes[start]!)) start += 1
  while (end > start && isBlank(nodes[end - 1]!)) end -= 1
  return nodes.slice(start, end)
}

function isBlank(node: InternalNode): boolean {
  return node.type === 'linebreak' || (node.type === 'text' && node.value.trim().length === 0)
}
