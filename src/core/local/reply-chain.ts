/**
 * 回复链(CONTEXT.md「回复链」,ticket 26):从楼层正文的引用标记里
 * 建立 quote 关系索引,再从任意一楼沿上下游展开成一条链。
 *
 * NGA 的引用有两种写法,都以 `[pid=<pid>,<tid>,<page>]Reply[/pid]` 标记被引用楼:
 *
 * - 引用:`[quote][pid=…]Reply[/pid] [b]Post by [uid=…]名字[/uid] (时间):[/b]…[/quote]`
 * - 回复:`[b]Reply to [pid=…]Reply[/pid] Post by (…)[/b]`(没有 quote 容器)
 *
 * 两种都算一条「本楼 → 被引用楼」的边。纯 TS,零 RN 依赖,可单测。
 */

import {
  childNodeLists,
  parseBBCode,
  type BBCodeNode,
  type QuoteNode,
  type StyleNode,
} from '../bbcode'

/** 一条引用指向的楼层。tid/页码来自 `[pid=a,b,c]` 的后两个参数,老写法可能缺。 */
export interface QuoteRef {
  readonly pid: number
  readonly tid?: number
  /** 被引用楼在原帖里的页码(发引用那一刻的口径,每页 20 楼固定,基本可信) */
  readonly page?: number
}

/** 建索引只需要每楼这三样。`lou` 用来给下游排序,贴条那类没有真实楼号的可不给。 */
export interface QuoteIndexFloor {
  readonly pid: number
  readonly lou?: number
  /** 正文 BBCode 原文 */
  readonly content: string
}

/** 已加载楼层的 quote 关系索引。 */
export interface QuoteIndex {
  /** pid → 它引用了谁(按正文里出现的顺序;第一条视作主引用) */
  readonly quotes: ReadonlyMap<number, readonly QuoteRef[]>
  /** pid → 谁引用了它(按楼号从小到大) */
  readonly quotedBy: ReadonlyMap<number, readonly number[]>
  /** 已加载楼层的 pid 集合;链上不在这里的节点要懒加载 */
  readonly loaded: ReadonlySet<number>
}

/** 回复链上的一个节点。`ref` 只在未加载时有用——懒加载靠它定位页码。 */
export interface ChainNode {
  readonly pid: number
  readonly role: 'upstream' | 'current' | 'downstream'
  /** false = 这一楼不在已加载集合里,要按 `ref.page` 懒加载(失败则降级占位) */
  readonly loaded: boolean
  readonly ref?: QuoteRef
}

const parseIntArg = (raw: string | undefined): number | undefined => {
  if (raw === undefined) return undefined
  const value = Number(raw.trim())
  return Number.isInteger(value) && value > 0 ? value : undefined
}

/** `[pid=a,b,c]` 节点 → 引用。pid 非正整数(空参、坏参)一律不算引用。 */
function refOfFloorRefNode(node: Extract<BBCodeNode, { type: 'floorRef' }>): QuoteRef | undefined {
  const pid = parseIntArg(node.args[0] ?? node.pid)
  if (pid === undefined) return undefined
  const tid = parseIntArg(node.args[1])
  const page = parseIntArg(node.args[2])
  return {
    pid,
    ...(tid === undefined ? {} : { tid }),
    ...(page === undefined ? {} : { page }),
  }
}

/**
 * 一段节点里的第一个 `[pid]` 引用。**不进嵌套的 quote**——
 * 引用块里再套一层引用块时,内层的 `[pid]` 是被引用楼自己的引用关系,
 * 算到本楼头上会把祖孙关系错接成父子。
 */
function firstFloorRef(nodes: readonly BBCodeNode[]): QuoteRef | undefined {
  for (const node of nodes) {
    if (node.type === 'floorRef') {
      const ref = refOfFloorRefNode(node)
      if (ref !== undefined) return ref
      continue
    }
    if (node.type === 'quote') continue
    for (const children of childNodeLists(node)) {
      const ref = firstFloorRef(children)
      if (ref !== undefined) return ref
    }
  }
  return undefined
}

/** 一个引用块指向哪一楼。渲染层用它决定「查看对话链」入口画不画。 */
export function quoteRefOf(node: QuoteNode): QuoteRef | undefined {
  return firstFloorRef(node.children)
}

/** `[b]Reply to [pid=…]…[/b]` 的回复头:粗体、第一段文字以 Reply to 开头。 */
function isReplyHeader(node: StyleNode): boolean {
  const firstText = (nodes: readonly BBCodeNode[]): string | undefined => {
    for (const child of nodes) {
      if (child.type === 'text') return child.value
      for (const children of childNodeLists(child)) {
        const value = firstText(children)
        if (value !== undefined) return value
      }
    }
    return undefined
  }
  return firstText(node.children)?.trimStart().startsWith('Reply to') === true
}

/**
 * 一楼正文里的全部引用:每个引用块取第一个 `[pid]`,回复头同理。
 * 正文里随手贴的楼层链接(不在这两种容器里)不算引用——那是提及,不是回复关系。
 */
export function extractQuoteRefs(nodes: readonly BBCodeNode[]): readonly QuoteRef[] {
  const refs: QuoteRef[] = []
  const walk = (list: readonly BBCodeNode[]): void => {
    for (const node of list) {
      if (node.type === 'quote') {
        const ref = quoteRefOf(node)
        if (ref !== undefined) refs.push(ref)
        continue
      }
      if (node.type === 'bold' && isReplyHeader(node)) {
        const ref = firstFloorRef(node.children)
        if (ref !== undefined) refs.push(ref)
        continue
      }
      for (const children of childNodeLists(node)) walk(children)
    }
  }
  walk(nodes)
  return refs
}

export interface BuildQuoteIndexOptions {
  /** 本帖 tid。给了就把指向别的主题的引用(跨帖引用)排除在链外 */
  readonly tid?: number
}

/**
 * 扫描已加载楼层建 quote 关系索引。
 *
 * - 同一楼引用同一目标多次只记一条;引用自己不记(自环没有意义,还会把链搅成死结)。
 * - 跨帖引用(`ref.tid` 与本帖不同)不进索引:它不是本帖楼层,链到不了。
 * - 楼层重复(同一楼在多页数据里都出现)以先到的为准。
 */
export function buildQuoteIndex(
  floors: readonly QuoteIndexFloor[],
  options: BuildQuoteIndexOptions = {},
): QuoteIndex {
  const quotes = new Map<number, readonly QuoteRef[]>()
  const quotedBy = new Map<number, number[]>()
  const loaded = new Set<number>()
  const louOf = new Map<number, number>()

  for (const floor of floors) {
    if (loaded.has(floor.pid)) continue
    loaded.add(floor.pid)
    if (floor.lou !== undefined) louOf.set(floor.pid, floor.lou)

    const seen = new Set<number>()
    const refs = extractQuoteRefs(parseBBCode(floor.content)).filter((ref) => {
      if (ref.pid === floor.pid) return false
      if (options.tid !== undefined && ref.tid !== undefined && ref.tid !== options.tid) return false
      if (seen.has(ref.pid)) return false
      seen.add(ref.pid)
      return true
    })
    if (refs.length === 0) continue

    quotes.set(floor.pid, refs)
    for (const ref of refs) {
      const list = quotedBy.get(ref.pid)
      if (list === undefined) quotedBy.set(ref.pid, [floor.pid])
      else list.push(floor.pid)
    }
  }

  // 下游按楼号排:一楼被多人引用时,链沿最早的那条回复走下去
  for (const list of quotedBy.values()) {
    list.sort((a, b) => (louOf.get(a) ?? Infinity) - (louOf.get(b) ?? Infinity) || a - b)
  }

  return { quotes, quotedBy, loaded }
}

/**
 * 从 `startPid` 展开回复链:上游沿「它引用了谁」走到头,下游沿「谁引用了它」走到头。
 *
 * - 一楼引用多楼时上游只沿第一条(主引用)走;一楼被多楼引用时下游沿最早的回复走——
 *   链是一条对话线,不是整棵树。
 * - 上游走到未加载的楼就停(那楼引用了谁只有加载后才知道),节点带着 `ref` 留给懒加载。
 * - 环引用(A 引 B、B 引 A)靠 visited 集合掐断,不会死循环。
 */
export function buildReplyChain(index: QuoteIndex, startPid: number): readonly ChainNode[] {
  const visited = new Set<number>([startPid])

  const upstream: ChainNode[] = []
  let cursor = startPid
  for (;;) {
    const ref = index.quotes.get(cursor)?.[0]
    if (ref === undefined || visited.has(ref.pid)) break
    visited.add(ref.pid)
    const loaded = index.loaded.has(ref.pid)
    upstream.unshift({ pid: ref.pid, role: 'upstream', loaded, ref })
    if (!loaded) break
    cursor = ref.pid
  }

  const downstream: ChainNode[] = []
  cursor = startPid
  for (;;) {
    const quoter = index.quotedBy.get(cursor)?.find((pid) => !visited.has(pid))
    if (quoter === undefined) break
    visited.add(quoter)
    downstream.push({ pid: quoter, role: 'downstream', loaded: index.loaded.has(quoter) })
    cursor = quoter
  }

  return [
    ...upstream,
    { pid: startPid, role: 'current', loaded: index.loaded.has(startPid) },
    ...downstream,
  ]
}

/** 「查看对话链(N 层)」的 N:从这一楼可追溯的链长(含它自己与未加载但可定位的楼)。 */
export function chainDepthOf(index: QuoteIndex, pid: number): number {
  return buildReplyChain(index, pid).length
}

/**
 * 把正文里的引用容器剥掉,留下这一楼自己说的话——回复链卡片用:
 * 链上一楼的上一层就画在它上面,卡片里再展开引用块只是同一段话出现两遍。
 * 剥的就是建索引认的那两种容器:顶层的 `[quote]` 与 `[b]Reply to …[/b]` 回复头。
 */
export function stripQuoteMarkup(nodes: readonly BBCodeNode[]): readonly BBCodeNode[] {
  const stripped = nodes.filter(
    (node) => node.type !== 'quote' && !(node.type === 'bold' && isReplyHeader(node)),
  )
  // 剥完开头常剩一两个空行(引用块与正文之间的换行),顺手掐掉
  let start = 0
  while (start < stripped.length && stripped[start]?.type === 'linebreak') start += 1
  return stripped.slice(start)
}
