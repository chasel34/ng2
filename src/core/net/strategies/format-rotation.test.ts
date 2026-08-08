import { describe, expect, it } from 'vitest'
import { createComboCache, interfaceKeyOf } from '../combo'
import { createNgaFetcher } from '../fetcher'
import type { HttpRequest, HttpResponse, HttpTransport } from '../transport'
import { createFormatRotationStrategy } from './format-rotation'

const utf8 = (text: string) => new TextEncoder().encode(text)

const OK = utf8('{"data":{"0":"ok"},"time":1}')
/** 被封的典型表现：返回一坨 HTML，洗不成 JSON（ADR-0002） */
const BLOCKED = utf8('<html><body>403 Forbidden</body></html>')

const HOSTS = ['https://bbs.nga.cn', 'https://ngabbs.com'] as const
const FORMATS = ['json', 'jsonLite'] as const

/** 组合的可读名，断言顺序时比生 URL 好读。 */
function comboOf(request: HttpRequest): string {
  const url = new URL(request.url)
  const lite = url.searchParams.get('lite')
  const format = lite === null ? `__output=${url.searchParams.get('__output')}` : `lite=${lite}`
  return `${format}@${url.origin}`
}

function fakeTransport(
  respond: (combo: string) => Partial<HttpResponse>,
): { transport: HttpTransport; combos: string[] } {
  const combos: string[] = []
  const transport: HttpTransport = (request) => {
    const combo = comboOf(request)
    combos.push(combo)
    const response = respond(combo)
    return Promise.resolve({
      status: response.status ?? 200,
      // 这个文件里的响应体都是 UTF-8 字面量（GBK 解码另有 encoding/ 的单测钉着）
      contentType: response.contentType ?? 'text/javascript; charset=UTF-8',
      body: response.body ?? OK,
    })
  }
  return { transport, combos }
}

/** 只有 `only` 这个组合是通的，其余一律返回封禁页。 */
function onlyWorking(only: string) {
  return fakeTransport((combo) =>
    combo === only ? { body: OK } : { status: 403, body: BLOCKED, contentType: 'text/html' },
  )
}

function rotatingFetcher(transport: HttpTransport, cache = createComboCache()) {
  return createNgaFetcher({
    transport,
    comboCache: cache,
    host: HOSTS[0],
    strategies: [
      createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 }),
    ],
  })
}

describe('createFormatRotationStrategy · 格式参数 × 域名的组合枚举', () => {
  it('封禁响应触发按序降级,直到某个组合通了', async () => {
    const { transport, combos } = onlyWorking('lite=js@https://ngabbs.com')
    const fetchNga = rotatingFetcher(transport)

    const result = await fetchNga({ path: 'thread.php', query: { fid: 650 } })

    expect(result.via).toBe('format-rotation')
    expect(combos).toEqual([
      '__output=8@https://bbs.nga.cn',
      'lite=js@https://bbs.nga.cn',
      '__output=8@https://ngabbs.com',
      'lite=js@https://ngabbs.com',
    ])
  })

  it('成功的组合按接口 key 记进缓存,下一次直接命中不再从头试', async () => {
    const cache = createComboCache()
    const { transport, combos } = onlyWorking('lite=js@https://ngabbs.com')
    const fetchNga = rotatingFetcher(transport, cache)
    const request = { path: 'thread.php', query: { fid: 650 } }

    await fetchNga(request)
    expect(cache.get(interfaceKeyOf(request))).toEqual({
      format: 'jsonLite',
      host: 'https://ngabbs.com',
    })

    combos.length = 0
    await fetchNga(request)

    expect(combos).toEqual(['lite=js@https://ngabbs.com'])
  })

  it('缓存是按接口分的:另一个接口还得自己从头试', async () => {
    const cache = createComboCache()
    const { transport, combos } = onlyWorking('lite=js@https://bbs.nga.cn')
    const fetchNga = rotatingFetcher(transport, cache)

    await fetchNga({ path: 'nuke.php', query: { __lib: 'noti', __act: 'get_all' } })
    combos.length = 0
    await fetchNga({ path: 'nuke.php', query: { __lib: 'ucp', __act: 'get' } })

    expect(combos).toEqual(['__output=8@https://bbs.nga.cn', 'lite=js@https://bbs.nga.cn'])
  })

  it('全组合都被封时,缓存里那个也清掉,免得下次还从它开局', async () => {
    const cache = createComboCache()
    const { transport } = onlyWorking('lite=js@https://bbs.nga.cn')
    const request = { path: 'thread.php', query: { fid: 650 } }
    await rotatingFetcher(transport, cache)(request)
    expect(cache.get(interfaceKeyOf(request))).toBeDefined()

    const blocked = fakeTransport(() => ({ status: 403, body: BLOCKED, contentType: 'text/html' }))
    await expect(rotatingFetcher(blocked.transport, cache)(request)).rejects.toMatchObject({
      kind: 'parse',
    })

    expect(cache.get(interfaceKeyOf(request))).toBeUndefined()
    expect(blocked.combos).toHaveLength(4)
  })

  it('业务错误(权限不足)不触发降级:那说明这个组合根本没被封', async () => {
    const { transport, combos } = fakeTransport(() => ({
      status: 403,
      body: utf8('{"error":{"code":8,"0":"您没有浏览该版面的权限"}}'),
    }))
    const fetchNga = rotatingFetcher(transport)

    await expect(fetchNga({ path: 'thread.php', query: { fid: 650 } })).rejects.toMatchObject({
      kind: 'server',
      message: expect.stringContaining('权限'),
    })
    expect(combos).toHaveLength(1)
  })

  it('业务错误也算这个组合是通的,一样记进缓存', async () => {
    const cache = createComboCache()
    const { transport } = fakeTransport((combo) =>
      combo === 'lite=js@https://bbs.nga.cn'
        ? { body: utf8('{"error":{"0":"2048:找不到主题"}}') }
        : { status: 403, body: BLOCKED, contentType: 'text/html' },
    )
    const request = { path: 'read.php', query: { tid: 1 } }

    await expect(rotatingFetcher(transport, cache)(request)).rejects.toMatchObject({
      kind: 'server',
    })

    expect(cache.get(interfaceKeyOf(request))).toEqual({
      format: 'jsonLite',
      host: 'https://bbs.nga.cn',
    })
  })

  it('组合数上限生效,不会让人等十几个来回', async () => {
    const { transport, combos } = fakeTransport(() => ({
      status: 403,
      body: BLOCKED,
      contentType: 'text/html',
    }))
    const fetchNga = createNgaFetcher({
      transport,
      host: HOSTS[0],
      strategies: [createFormatRotationStrategy({ maxAttempts: 3 })],
    })

    await expect(fetchNga({ path: 'thread.php' })).rejects.toMatchObject({ kind: 'parse' })
    expect(combos).toHaveLength(3)
  })

  it('每次重试前重建 HTTP client(第一次用现成的)', async () => {
    let built = 0
    const { transport } = onlyWorking('lite=js@https://ngabbs.com')
    const fetchNga = createNgaFetcher({
      createTransport: () => {
        built += 1
        return transport
      },
      host: HOSTS[0],
      strategies: [
        createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 }),
      ],
    })

    await fetchNga({ path: 'thread.php' })

    // 建 fetcher 时一次 + 第 2/3/4 次尝试各重建一次
    expect(built).toBe(4)
  })

  it('调用方指定的域名排第一,被封了照样往下轮换', async () => {
    const { transport, combos } = onlyWorking('__output=8@https://bbs.nga.cn')
    const fetchNga = rotatingFetcher(transport)

    await fetchNga({ path: 'thread.php', host: 'https://nga.178.com' })

    expect(combos[0]).toBe('__output=8@https://nga.178.com')
    expect(combos.at(-1)).toBe('__output=8@https://bbs.nga.cn')
  })

  it('取消请求不当被封,一次就停', async () => {
    const controller = new AbortController()
    const combos: string[] = []
    const transport: HttpTransport = (request) => {
      combos.push(comboOf(request))
      controller.abort()
      return Promise.reject(new DOMException('Aborted', 'AbortError'))
    }
    const fetchNga = rotatingFetcher(transport)

    await expect(
      fetchNga({ path: 'thread.php', signal: controller.signal }),
    ).rejects.toMatchObject({ kind: 'network', retryable: false })
    expect(combos).toHaveLength(1)
  })
})
