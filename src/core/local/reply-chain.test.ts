import { describe, expect, it } from 'vitest'

import { parseBBCode } from '../bbcode'
import {
  buildQuoteIndex,
  buildReplyChain,
  chainDepthOf,
  extractQuoteRefs,
  quoteRefOf,
  stripQuoteMarkup,
} from './reply-chain'

const TID = 45150945

/** 引用块写法(NGA「引用」按钮的产物)。 */
const quoteOf = (pid: number, page: number, body: string): string =>
  `[quote][pid=${pid},${TID},${page}]Reply[/pid] [b]Post by [uid=41417929]张三[/uid] (2026-08-07 12:00):[/b]<br/>${body}[/quote]`

/** 回复头写法(NGA「回复」按钮的产物,没有 quote 容器)。 */
const replyTo = (pid: number, page: number): string =>
  `[b]Reply to [pid=${pid},${TID},${page}]Reply[/pid] Post by [uid=233]李四[/uid] (2026-08-07 13:00)[/b]<br/>`

describe('extractQuoteRefs / quoteRefOf', () => {
  it('引用块里的 [pid] 认成引用,tid 与页码来自后两个参数', () => {
    const refs = extractQuoteRefs(parseBBCode(quoteOf(123456, 3, '原话') + '我的看法'))
    expect(refs).toEqual([{ pid: 123456, tid: TID, page: 3 }])
  })

  it('[b]Reply to …[/b] 回复头也认成引用', () => {
    const refs = extractQuoteRefs(parseBBCode(replyTo(777, 2) + '同意楼上'))
    expect(refs).toEqual([{ pid: 777, tid: TID, page: 2 }])
  })

  it('正文里随手贴的 [pid] 链接不算引用', () => {
    const refs = extractQuoteRefs(parseBBCode(`看看这楼 [pid=99,${TID},1]Reply[/pid] 说的`))
    expect(refs).toEqual([])
  })

  it('嵌套引用块只认外层的 [pid],内层是祖辈关系不算到本楼头上', () => {
    const inner = `[quote][pid=11,${TID},1]Reply[/pid]祖辈原话[/quote]`
    const outer = `[quote][pid=22,${TID},1]Reply[/pid] [b]Post by [uid=1]某人[/uid]:[/b]${inner}父辈原话[/quote]`
    expect(extractQuoteRefs(parseBBCode(outer))).toEqual([{ pid: 22, tid: TID, page: 1 }])
  })

  it('老写法 [pid=123] 缺 tid/页码时只有 pid;坏参数不算引用', () => {
    expect(extractQuoteRefs(parseBBCode('[quote][pid=123]Reply[/pid]原话[/quote]'))).toEqual([
      { pid: 123 },
    ])
    expect(extractQuoteRefs(parseBBCode('[quote][pid=abc]Reply[/pid]原话[/quote]'))).toEqual([])
  })

  it('quoteRefOf 直接对 quote 节点取引用', () => {
    const [node] = parseBBCode(quoteOf(5, 1, '原话'))
    expect(node?.type).toBe('quote')
    if (node?.type !== 'quote') return
    expect(quoteRefOf(node)).toEqual({ pid: 5, tid: TID, page: 1 })
  })
})

describe('buildQuoteIndex', () => {
  it('建双向索引:quotes 记它引了谁,quotedBy 记谁引了它', () => {
    const index = buildQuoteIndex(
      [
        { pid: 1, lou: 0, content: '主楼' },
        { pid: 2, lou: 1, content: quoteOf(1, 1, '主楼原话') + '顶' },
        { pid: 3, lou: 2, content: quoteOf(1, 1, '主楼原话') + '再顶' },
      ],
      { tid: TID },
    )
    expect(index.quotes.get(2)).toEqual([{ pid: 1, tid: TID, page: 1 }])
    expect(index.quotedBy.get(1)).toEqual([2, 3])
    expect(index.loaded).toEqual(new Set([1, 2, 3]))
  })

  it('引用自己与跨帖引用不进索引;同目标引两次只记一条', () => {
    const index = buildQuoteIndex(
      [
        { pid: 7, lou: 3, content: quoteOf(7, 1, '自己') },
        { pid: 8, lou: 4, content: `[quote][pid=555,99999,1]Reply[/pid]别帖的话[/quote]` },
        { pid: 9, lou: 5, content: quoteOf(7, 1, 'a') + quoteOf(7, 1, 'b') },
      ],
      { tid: TID },
    )
    expect(index.quotes.has(7)).toBe(false)
    expect(index.quotes.has(8)).toBe(false)
    expect(index.quotes.get(9)).toEqual([{ pid: 7, tid: TID, page: 1 }])
  })

  it('下游按楼号排序,与楼层加载顺序无关', () => {
    const index = buildQuoteIndex(
      [
        { pid: 30, lou: 9, content: quoteOf(10, 1, '原话') },
        { pid: 20, lou: 4, content: quoteOf(10, 1, '原话') },
        { pid: 10, lou: 1, content: '被引的楼' },
      ],
      { tid: TID },
    )
    expect(index.quotedBy.get(10)).toEqual([20, 30])
  })
})

describe('buildReplyChain', () => {
  it('上游沿引用走到头、下游沿最早的回复走到头,当前楼在中间', () => {
    const index = buildQuoteIndex(
      [
        { pid: 1, lou: 0, content: '主楼' },
        { pid: 2, lou: 1, content: quoteOf(1, 1, '主楼原话') + '一楼' },
        { pid: 3, lou: 2, content: quoteOf(2, 1, '一楼原话') + '二楼' },
        { pid: 4, lou: 3, content: quoteOf(3, 1, '二楼原话') + '三楼' },
      ],
      { tid: TID },
    )
    expect(buildReplyChain(index, 3)).toEqual([
      { pid: 1, role: 'upstream', loaded: true, ref: { pid: 1, tid: TID, page: 1 } },
      { pid: 2, role: 'upstream', loaded: true, ref: { pid: 2, tid: TID, page: 1 } },
      { pid: 3, role: 'current', loaded: true },
      { pid: 4, role: 'downstream', loaded: true },
    ])
    expect(chainDepthOf(index, 3)).toBe(4)
  })

  it('跨页引用:被引楼不在已加载集合时,节点带 ref 供懒加载,上游到此为止', () => {
    const index = buildQuoteIndex(
      [{ pid: 100, lou: 21, content: quoteOf(66, 1, '第一页的原话') + '回它' }],
      { tid: TID },
    )
    expect(buildReplyChain(index, 100)).toEqual([
      { pid: 66, role: 'upstream', loaded: false, ref: { pid: 66, tid: TID, page: 1 } },
      { pid: 100, role: 'current', loaded: true },
    ])
    expect(chainDepthOf(index, 100)).toBe(2)
  })

  it('引用楼缺失且没有页码信息:节点照样在链上,只是没有定位手段', () => {
    const index = buildQuoteIndex(
      [{ pid: 100, lou: 5, content: '[quote][pid=66]Reply[/pid]老写法引用[/quote]' }],
      { tid: TID },
    )
    const [head] = buildReplyChain(index, 100)
    expect(head).toEqual({ pid: 66, role: 'upstream', loaded: false, ref: { pid: 66 } })
  })

  it('环引用不死循环:A 引 B、B 引 A', () => {
    const index = buildQuoteIndex(
      [
        { pid: 1, lou: 1, content: quoteOf(2, 1, 'B 的话') + 'A' },
        { pid: 2, lou: 2, content: quoteOf(1, 1, 'A 的话') + 'B' },
      ],
      { tid: TID },
    )
    expect(buildReplyChain(index, 1)).toEqual([
      { pid: 2, role: 'upstream', loaded: true, ref: { pid: 2, tid: TID, page: 1 } },
      { pid: 1, role: 'current', loaded: true },
    ])
    // 三节点的环同样掐得断
    const ring = buildQuoteIndex(
      [
        { pid: 1, lou: 1, content: quoteOf(3, 1, 'x') },
        { pid: 2, lou: 2, content: quoteOf(1, 1, 'x') },
        { pid: 3, lou: 3, content: quoteOf(2, 1, 'x') },
      ],
      { tid: TID },
    )
    expect(buildReplyChain(ring, 2).map((node) => node.pid)).toEqual([3, 1, 2])
  })

  it('孤楼(没引用也没被引用)的链只有它自己', () => {
    const index = buildQuoteIndex([{ pid: 9, lou: 9, content: '就一句话' }], { tid: TID })
    expect(buildReplyChain(index, 9)).toEqual([{ pid: 9, role: 'current', loaded: true }])
    expect(chainDepthOf(index, 9)).toBe(1)
  })
})

describe('stripQuoteMarkup', () => {
  it('剥掉引用块与回复头,留下本楼自己的话', () => {
    const nodes = parseBBCode(quoteOf(1, 1, '原话') + '我的看法')
    const stripped = stripQuoteMarkup(nodes)
    expect(stripped).toEqual([{ type: 'text', value: '我的看法' }])

    const replied = stripQuoteMarkup(parseBBCode(replyTo(7, 1) + '同意楼上'))
    expect(replied).toEqual([{ type: 'text', value: '同意楼上' }])
  })

  it('普通粗体不剥,只剥 Reply to 回复头', () => {
    const nodes = parseBBCode('[b]重点[/b]内容')
    expect(stripQuoteMarkup(nodes)).toEqual(nodes)
  })
})
