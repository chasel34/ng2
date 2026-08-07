import { unescapeNgaText } from './entities'
import type { InternalNode, OpenTag } from './internal'
import { normalize } from './normalize'
import { CONTAINER_BUILDERS, isRawTag, RAW_BUILDERS, SELF_CLOSING_TAGS } from './tags'
import type { BBCodeNode } from './types'

interface Frame {
  readonly open: OpenTag
  readonly children: InternalNode[]
}

/**
 * 嵌套深度上限。正文来自服务端,谁也不保证不会拿到几千层嵌套的畸形内容,
 * 而 AST 的归一化是递归的——超过这个深度的开标签一律当普通文本。
 * 真实楼层的引用套引用撑死十来层,64 层留足了余量。
 */
const MAX_NESTING_DEPTH = 64

/** 把楼层 BBCode 原文解析成 AST。任何输入都返回节点数组,不抛异常。 */
export function parseBBCode(source: string): BBCodeNode[] {
  if (source.length === 0) return []

  /** 找闭标签时按小写比对,预先算一次,免得每个标签都把全文重新小写一遍。 */
  const lowerSource = source.toLowerCase()
  const root: Frame = { open: { name: '', raw: '', length: 0 }, children: [] }
  const stack: Frame[] = [root]

  /** 尚未落成节点的原始文本,攒着是为了让相邻文本自然合并。 */
  let pending = ''
  const flushText = (): void => {
    if (pending.length === 0) return
    const value = unescapeNgaText(pending)
    pending = ''
    if (value.length > 0) pushNode(stack, { type: 'text', value })
  }

  /** 只有普通文本会打断「行首」,标签本身不算,免得 `[quote]===标题===` 认不出来。 */
  let atLineStart = true
  let index = 0

  while (index < source.length) {
    const breakLength = matchLineBreak(source, index)
    if (breakLength > 0) {
      flushText()
      pushNode(stack, { type: 'linebreak' })
      index += breakLength
      atLineStart = true
      continue
    }

    if (atLineStart && source[index] === '=') {
      const line = lineAt(source, index)
      const ruled = matchHeadingOrDivider(line)
      if (ruled !== null) {
        flushText()
        pushNode(stack, ruled)
        index += line.length
        continue
      }
    }

    if (source[index] !== '[') {
      pending += source[index]
      index += 1
      atLineStart = false
      continue
    }

    const close = matchCloseTag(source, index)
    if (close !== null && findFrame(stack, close.name) > 0) {
      flushText()
      closeFrames(stack, close.name)
      index += close.length
      continue
    }

    const smiley = matchAt(SMILEY_TAG, source, index)
    if (smiley !== null) {
      flushText()
      pushNode(stack, { type: 'smiley', code: smiley[1]! })
      index += smiley[0].length
      continue
    }

    const mention = matchAt(MENTION_TAG, source, index)
    if (mention !== null) {
      flushText()
      pushNode(stack, { type: 'mention', username: unescapeNgaText(mention[1]!) })
      index += mention[0].length
      continue
    }

    const open = matchOpenTag(source, index)

    // `[dice XdY]` 没有闭标签,表达式藏在属性位。
    if (open !== null && open.name === 'dice' && (open.attrText ?? '').trim().length > 0) {
      flushText()
      pushNode(stack, { type: 'dice', expression: open.attrText!.trim() })
      index += open.length
      continue
    }

    if (open !== null && isRawTag(open)) {
      const raw = readRawTag(source, lowerSource, index, open, stack)
      if (raw !== null) {
        flushText()
        pushNode(stack, raw.node)
        index += raw.length
        continue
      }
    }

    if (
      open !== null &&
      CONTAINER_BUILDERS[open.name] !== undefined &&
      stack.length <= MAX_NESTING_DEPTH
    ) {
      flushText()
      // `[*]` 之间没有闭标签,遇到下一个就把上一个收掉。
      if (SELF_CLOSING_TAGS.has(open.name) && stack[stack.length - 1]!.open.name === open.name) {
        buildFrame(stack)
      }
      stack.push({ open, children: [] })
      index += open.length
      continue
    }

    // 未知标签(以及找不到对应开标签的闭标签)原样透传成文本。
    const literal = open?.raw ?? close?.raw
    if (literal !== undefined) {
      pending += literal
      index += literal.length
      continue
    }

    pending += source[index]
    index += 1
    atLineStart = false
  }

  flushText()
  while (stack.length > 1) degradeFrame(stack)
  return normalize(root.children)
}

// 一律用 sticky(`y`)从指定下标起匹配:全文可能上万字,每个标签都切一次子串会退化成 O(n²)。
const LINE_BREAK_TAG = /<br\s*\/?>/iy
const ANY_LINE_BREAK_TAG = /<br\s*\/?>/gi
const LINE_END = /\r\n|\r|\n|<br\s*\/?>/gi
const OPEN_TAG = /\[([a-zA-Z*@][a-zA-Z0-9_]*)(?:(=|\s+)([^\]]*))?\]/y
const CLOSE_TAG = /\[\/([a-zA-Z*@][a-zA-Z0-9_]*)\s*\]/y
const MENTION_TAG = /\[@([^[\]]+)\]/y
const SMILEY_TAG = /\[s:([^[\]]+)\]/y
const ATTR = /([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*"([^"]*)"|([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(\S+)/g
const DIVIDER_LINE = /^={4,}$/
const HEADING_LINE = /^={3,}(.+?)={3,}$/

/** 从 `index` 处起做 sticky 匹配。 */
function matchAt(pattern: RegExp, source: string, index: number): RegExpExecArray | null {
  pattern.lastIndex = index
  return pattern.exec(source)
}

function matchLineBreak(source: string, index: number): number {
  const char = source[index]
  if (char === '\n') return 1
  if (char === '\r') return source[index + 1] === '\n' ? 2 : 1
  if (char !== '<') return 0
  const match = matchAt(LINE_BREAK_TAG, source, index)
  return match === null ? 0 : match[0].length
}

/** 取从 index 到本行结尾(不含换行)的文本。 */
function lineAt(source: string, index: number): string {
  LINE_END.lastIndex = index
  const end = LINE_END.exec(source)
  return source.slice(index, end === null ? undefined : end.index)
}

/** `======` 是分割线,`===标题===` 是标题,两者都要独占一行。 */
function matchHeadingOrDivider(line: string): BBCodeNode | null {
  if (DIVIDER_LINE.test(line)) return { type: 'divider' }
  const heading = HEADING_LINE.exec(line)
  if (heading === null || heading[1]!.trim().length === 0) return null
  return { type: 'heading', children: parseBBCode(heading[1]!) }
}

function matchOpenTag(source: string, index: number): OpenTag | null {
  const match = matchAt(OPEN_TAG, source, index)
  if (match === null) return null
  const separator = match[2]
  const rest = match[3]
  return {
    name: match[1]!.toLowerCase(),
    ...(separator === '=' ? { value: rest ?? '' } : {}),
    ...(separator !== undefined && separator !== '='
      ? { attrs: parseAttrs(rest ?? ''), attrText: rest ?? '' }
      : {}),
    raw: match[0],
    length: match[0].length,
  }
}

function matchCloseTag(
  source: string,
  index: number,
): { name: string; raw: string; length: number } | null {
  const match = matchAt(CLOSE_TAG, source, index)
  if (match === null) return null
  return { name: match[1]!.toLowerCase(), raw: match[0], length: match[0].length }
}

function parseAttrs(input: string): Record<string, string> {
  const attrs: Record<string, string> = {}
  for (const match of input.matchAll(ATTR)) {
    const key = (match[1] ?? match[3])!.toLowerCase()
    attrs[key] = (match[2] ?? match[4])!
  }
  return attrs
}

/**
 * 读一个内容不解析标签的标签(`[code]`、`[img]` 等),返回节点与整段消耗的长度。
 *
 * 找不到自己的闭标签,或者外层某个标签的闭标签来得更早,就返回 null——
 * 否则 `[quote][code]abc[/quote]` 会把 `[/quote]` 连同后面的正文一起吞掉。
 */
function readRawTag(
  source: string,
  lowerSource: string,
  index: number,
  open: OpenTag,
  stack: readonly Frame[],
): { node: BBCodeNode; length: number } | null {
  const contentStart = index + open.length
  const closeIndex = lowerSource.indexOf(`[/${open.name}]`, contentStart)
  if (closeIndex < 0) return null
  if (closeIndex > enclosingCloseIndex(lowerSource, contentStart, stack)) return null
  const raw = source.slice(contentStart, closeIndex)
  const value = unescapeNgaText(raw.replace(ANY_LINE_BREAK_TAG, '\n'))
  return {
    node: RAW_BUILDERS[open.name]!(open, value),
    length: closeIndex + open.name.length + 3 - index,
  }
}

/** 栈上任一未闭合标签的闭标签,最早出现在哪里。 */
function enclosingCloseIndex(lowerSource: string, from: number, stack: readonly Frame[]): number {
  let earliest = Number.POSITIVE_INFINITY
  for (let i = 1; i < stack.length; i += 1) {
    const at = lowerSource.indexOf(`[/${stack[i]!.open.name}]`, from)
    if (at >= 0 && at < earliest) earliest = at
  }
  return earliest
}

/** 返回栈内最靠近栈顶的同名 frame 下标;0 表示没找到(0 是 root)。 */
function findFrame(stack: readonly Frame[], name: string): number {
  for (let i = stack.length - 1; i > 0; i -= 1) {
    if (stack[i]!.open.name === name) return i
  }
  return 0
}

/** 闭合到指定标签为止;跨过的未闭合 frame 按原样降级成文本。 */
function closeFrames(stack: Frame[], name: string): void {
  const target = findFrame(stack, name)
  while (stack.length - 1 > target) degradeFrame(stack)
  buildFrame(stack)
}

function buildFrame(stack: Frame[]): void {
  const frame = stack.pop()!
  pushNode(stack, CONTAINER_BUILDERS[frame.open.name]!(frame.open, frame.children))
}

/** 未闭合标签:开标签文本原样保留,内容直接并入父节点,一个字都不丢。 */
function degradeFrame(stack: Frame[]): void {
  if (SELF_CLOSING_TAGS.has(stack[stack.length - 1]!.open.name)) {
    buildFrame(stack)
    return
  }
  const frame = stack.pop()!
  pushNode(stack, { type: 'text', value: unescapeNgaText(frame.open.raw) })
  for (const node of frame.children) pushNode(stack, node)
}

function pushNode(stack: readonly Frame[], node: InternalNode): void {
  stack[stack.length - 1]!.children.push(node)
}
