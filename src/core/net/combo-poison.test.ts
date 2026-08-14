/**
 * 「一次瞬时失败把 thread.php 永久钉死在坏组合上」的回归测试
 * （2026-08-13，M4 走查里的「冷启动后第 4 个版块起全部显示『这个版块还没有主题』」）。
 *
 * 当时的链路：某次请求撞上限流 → `format-rotation` 换下一个组合 → 那个组合返回了
 * 一份**能洗成 JSON 但没有 `__T`** 的东西 → 被当成「成功」记进 `comboCache`
 * （key 是接口粒度的 `thread.php`，版块/搜索/收藏夹/热帖共用一条）→ 此后每个请求
 * 第一发就「成功」，永远轮不到别的组合，`error === null` 且 0 条主题，
 * 一路走到 UI 变成空态，**只有杀进程能复位**。
 *
 * 这个文件钉住修完之后的三条性质：
 * 1. 形状不对的响应不是成功——链会继续轮换，坏组合进不了缓存；
 * 2. 全都拿不到主题列表时是**报错**，不是「这个版块是空的」；
 * 3. 缓存有保质期，过期后会重新试探默认组合（自愈，不必杀进程）。
 */
import { describe, expect, it } from 'vitest'

import { fetchTopicList } from '../api/topic-list'
import { createComboCache } from './combo'
import { parseNgaJson } from './envelope'
import { NgaError } from './errors'
import { createNgaFetcher } from './fetcher'
import { createFormatRotationStrategy } from './strategies/format-rotation'
import type { HttpRequest, HttpResponse, HttpTransport } from './transport'

const utf8 = (text: string) => new TextEncoder().encode(text)

/** 一页正常的主题列表 */
const GOOD = utf8(
  '{"data":{"__T":{"0":{"tid":1,"subject":"标题","author":"a"}},' +
    '"__F":{"fid":650,"name":"原神"},"__ROWS":100,"__T__ROWS_PAGE":35},"time":1}',
)
/** 真的空版块：服务端照样给 `__T`/`__F`/`__ROWS`，只是一条都没有 */
const EMPTY_BOARD = utf8(
  '{"data":{"__T":{},"__F":{"fid":650,"name":"原神"},"__ROWS":0,"__T__ROWS_PAGE":35},"time":1}',
)
/** 事故里那个形态：能解析，但根本不是一页主题列表 */
const NO_TOPIC_LIST = utf8('{"data":{"__CU":{"uid":10000001}},"time":1}')
/** 被封的典型：一坨 HTML */
const BLOCKED = utf8('<html>403</html>')

function formatOf(request: HttpRequest): string {
  const url = new URL(request.url)
  const lite = url.searchParams.get('lite')
  return lite === null ? `__output=${url.searchParams.get('__output')}` : `lite=${lite}`
}

function comboOf(request: HttpRequest): string {
  return `${formatOf(request)}@${new URL(request.url).origin}`
}

function fetcherWith(
  respond: (combo: string, index: number) => Uint8Array,
  cache = createComboCache(),
) {
  const seen: string[] = []
  const transport: HttpTransport = (request) => {
    const combo = comboOf(request)
    seen.push(combo)
    const body = respond(combo, seen.length)
    const html = body === BLOCKED
    return Promise.resolve<HttpResponse>({
      status: html ? 403 : 200,
      contentType: html ? 'text/html' : 'text/javascript; charset=UTF-8',
      body,
    })
  }
  return {
    seen,
    cache,
    fetchNga: createNgaFetcher({
      transport,
      comboCache: cache,
      host: 'https://bbs.nga.cn',
      strategies: [createFormatRotationStrategy()],
    }),
  }
}

const openBoard = (fetchNga: ReturnType<typeof fetcherWith>['fetchNga'], fid: number) =>
  fetchTopicList(fetchNga, { boardId: fid, kind: 'board', page: 1 })

describe('thread.php 的成功组合缓存不会被「能解析但没有主题列表」的响应毒化', () => {
  it('一次瞬时失败之后，坏组合不进缓存，后面的版块照常出主题', async () => {
    // `__output=8@bbs` 只在第 4 个版块那一次被封（模拟一次限流），之后立刻恢复；
    // 别的组合永远只给「能解析但没有 __T」的东西
    let blockedOnce = false
    const { fetchNga, cache } = fetcherWith((combo) => {
      if (combo === '__output=8@https://bbs.nga.cn') {
        if (blockedOnce) return GOOD
        blockedOnce = true
        return BLOCKED
      }
      return combo.startsWith('__output=8') ? GOOD : NO_TOPIC_LIST
    })

    // 第一发就被封 → 轮换 → lite=js 拿到没有 __T 的东西（不算成功）→ 继续换域名 → 通了
    const first = await openBoard(fetchNga, 414)
    expect(first.topics).toHaveLength(1)

    // 缓存里记下的是真正给出了主题列表的那个组合，不是那个「能解析」的
    expect(cache.get('thread.php')?.format).toBe('json')

    // 之后每个版块都正常——事故里从这里开始全是空的
    for (const fid of [428, 481, 650]) {
      expect((await openBoard(fetchNga, fid)).topics).toHaveLength(1)
    }
  })

  it('所有组合都拿不到主题列表时是报错，不是「这个版块还没有主题」', async () => {
    const { fetchNga, cache, seen } = fetcherWith(() => NO_TOPIC_LIST)

    await expect(openBoard(fetchNga, 650)).rejects.toThrow(/没有主题列表结构/)
    // 一个都不许进缓存：进了就等于把下一次也钉死在这儿
    expect(cache.get('thread.php')).toBeUndefined()
    // 而且真的把组合空间跑完了（不是第一发就当成功收工）
    expect(new Set(seen).size).toBeGreaterThan(1)
  })

  it('真的空版块仍然是「成功的 0 条」，不报错', async () => {
    const { fetchNga, cache } = fetcherWith(() => EMPTY_BOARD)

    const list = await openBoard(fetchNga, 650)
    expect(list.topics).toEqual([])
    // 服务端确实按主题列表回了话——UI 靠它区分「没帖」和「没拿到」
    expect(list.listStructure).toBe(true)
    expect(cache.get('thread.php')?.format).toBe('json')
  })

  it('缓存过期后会重新试探默认组合（自愈，不必杀进程）', async () => {
    let clock = 1_000_000
    const cache = createComboCache({ ttlMs: 60_000, now: () => clock })
    // 默认域名一开始是通的，中途整个被封（格式换了也没用），之后又恢复
    let blockDefault = false
    const { fetchNga, seen } = fetcherWith(
      (combo) => (blockDefault && combo.endsWith('@https://bbs.nga.cn') ? BLOCKED : GOOD),
      cache,
    )

    await openBoard(fetchNga, 650)
    expect(cache.get('thread.php')?.host).toBe('https://bbs.nga.cn')

    // 被封 → 换到镜像域名并记住它
    blockDefault = true
    await openBoard(fetchNga, 321)
    expect(cache.get('thread.php')?.host).toBe('https://ngabbs.com')

    // 封解除了，但缓存还在保质期内：继续用镜像域名，一次都不回头试
    blockDefault = false
    seen.length = 0
    await openBoard(fetchNga, 436)
    expect(seen).toEqual(['__output=8@https://ngabbs.com'])

    // 过了保质期：从默认组合重新试探
    clock += 60_001
    seen.length = 0
    await openBoard(fetchNga, 414)
    expect(seen[0]).toBe('__output=8@https://bbs.nga.cn')
    expect(cache.get('thread.php')?.host).toBe('https://bbs.nga.cn')
  })
})

describe('「能解析」不等于「拿到了想要的东西」', () => {
  it('顶层既没有 data 也没有 error 时报解析错，不是一份空数据', () => {
    // 事故的必要条件之一：以前这里会把整个顶层当 data
    const error = (() => {
      try {
        parseNgaJson('{"result":"ok","time":1}', 'direct')
        return undefined
      } catch (cause) {
        return cause as NgaError
      }
    })()
    expect(error?.kind).toBe('parse')
    expect(error?.retryable).toBe(true)
  })

  it('error 是数组时也当服务端错误，把原话带出来', () => {
    const error = (() => {
      try {
        parseNgaJson('{"error":["您的访问速度过快"]}')
        return undefined
      } catch (cause) {
        return cause as NgaError
      }
    })()
    expect(error?.kind).toBe('server')
    expect(error?.message).toBe('您的访问速度过快')
  })
})
