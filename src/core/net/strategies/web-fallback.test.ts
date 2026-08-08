import { describe, expect, it } from 'vitest'

import { fixtureContentType, readFixtureBytes } from '../__fixtures__'
import { createComboCache } from '../combo'
import { createNgaFetcher } from '../fetcher'
import { isRecord } from '../is-record'
import type { HttpRequest, HttpResponse, HttpTransport } from '../transport'
import { createFormatRotationStrategy } from './format-rotation'
import { createWebFallbackStrategy, type WebFallbackMode } from './web-fallback'

const utf8 = (text: string) => new TextEncoder().encode(text)

const BLOCKED = utf8('<html><body>403 Forbidden</body></html>')
const OK_JSON = utf8('{"data":{"__R":{"0":{"content":"原生","lou":0}}},"time":1}')

const WEB_PAGE = readFixtureBytes('readWebAnonymousHotReply')
const WEB_PAGE_TYPE = fixtureContentType('readWebAnonymousHotReply')

/** 不带格式参数 = 网页版；带 `__output`/`lite` 的都是原生接口。 */
const isWebRequest = (request: HttpRequest) =>
  !request.url.includes('__output=') && !request.url.includes('lite=')

function fakeTransport(respond: (request: HttpRequest) => Partial<HttpResponse>): {
  transport: HttpTransport
  requests: HttpRequest[]
} {
  const requests: HttpRequest[] = []
  const transport: HttpTransport = (request) => {
    requests.push(request)
    const response = respond(request)
    return Promise.resolve({
      status: response.status ?? 200,
      contentType: response.contentType ?? 'text/javascript; charset=UTF-8',
      body: response.body ?? OK_JSON,
    })
  }
  return { transport, requests }
}

/** 原生接口全被封、网页版还通——这一档存在的理由就是这个局面。 */
const nativeBlocked = () =>
  fakeTransport((request) =>
    isWebRequest(request)
      ? { body: WEB_PAGE, contentType: WEB_PAGE_TYPE }
      : { status: 403, body: BLOCKED, contentType: 'text/html' },
  )

/** 两条都通:用来看档位有没有真的改变「谁先上」。 */
const bothWork = () =>
  fakeTransport((request) =>
    isWebRequest(request) ? { body: WEB_PAGE, contentType: WEB_PAGE_TYPE } : { body: OK_JSON },
  )

function chainFetcher(transport: HttpTransport, mode: WebFallbackMode) {
  const getMode = () => mode
  return createNgaFetcher({
    transport,
    comboCache: createComboCache(),
    strategies: [
      createWebFallbackStrategy({ placement: 'primary', getMode }),
      createFormatRotationStrategy({ formats: ['json'], hosts: ['https://bbs.nga.cn'] }),
      createWebFallbackStrategy({ placement: 'secondary', getMode }),
    ],
  })
}

const READ = { path: 'read.php', query: { tid: 46186286, page: 1 } } as const

/** 反解出来的信封长得跟 `__output=8` 一样,所以下游认得出这是第几楼。 */
const firstFloorContent = (data: unknown): unknown => {
  if (!isRecord(data) || !isRecord(data.__R) || !isRecord(data.__R['0'])) return undefined
  return data.__R['0'].content
}

describe('createWebFallbackStrategy · 档位', () => {
  it('secondary(默认):原生先上,全垮了才反解,产物与 JSON 路线同构', async () => {
    const { transport, requests } = nativeBlocked()

    const result = await chainFetcher(transport, 'secondary')(READ)

    expect(result.via).toBe('web-fallback')
    expect(requests.map(isWebRequest)).toEqual([false, true])
    expect(firstFloorContent(result.data)).toContain('论坛里熟人有点多')
  })

  it('primary:read.php 先反解,一次原生都不打', async () => {
    const { transport, requests } = bothWork()

    const result = await chainFetcher(transport, 'primary')(READ)

    expect(result.via).toBe('web-fallback')
    expect(requests.map(isWebRequest)).toEqual([true])
  })

  it('primary:反解不出来还能退回原生', async () => {
    const { transport, requests } = fakeTransport((request) =>
      isWebRequest(request)
        ? { body: BLOCKED, contentType: 'text/html' }
        : { body: OK_JSON },
    )

    const result = await chainFetcher(transport, 'primary')(READ)

    expect(result.via).toBe('format-rotation')
    expect(requests.map(isWebRequest)).toEqual([true, false])
  })

  it('only:反解垮了就是终点,不再退回原生', async () => {
    const { transport, requests } = fakeTransport((request) =>
      isWebRequest(request)
        ? { body: BLOCKED, contentType: 'text/html' }
        : { body: OK_JSON },
    )

    await expect(chainFetcher(transport, 'only')(READ)).rejects.toMatchObject({
      kind: 'parse',
      via: 'web-fallback',
    })
    expect(requests.map(isWebRequest)).toEqual([true])
  })

  it('disabled:两个位置都不启用,链上当这一档不存在', async () => {
    const { transport, requests } = nativeBlocked()

    await expect(chainFetcher(transport, 'disabled')(READ)).rejects.toMatchObject({
      kind: 'parse',
    })
    expect(requests.map(isWebRequest)).toEqual([false])
  })
})

describe('createWebFallbackStrategy · 适用范围', () => {
  it('别的接口一律跳过——`only` 也一样,那档说的是 read.php 只走反解', async () => {
    const { transport, requests } = bothWork()

    const result = await chainFetcher(transport, 'only')({ path: 'thread.php', query: { fid: -7 } })

    expect(result.via).toBe('format-rotation')
    expect(requests.map(isWebRequest)).toEqual([false])
  })

  it('网页版返回 msgcode 错误时按服务端语义错误抛,不再往下试', async () => {
    const { transport, requests } = fakeTransport(() => ({
      body: readFixtureBytes('readWebNotFound'),
      contentType: fixtureContentType('readWebNotFound'),
    }))

    await expect(chainFetcher(transport, 'only')({ path: 'read.php', query: { tid: 1 } })).rejects.toMatchObject(
      { kind: 'server', message: '2048:找不到主题' },
    )
    expect(requests).toHaveLength(1)
  })

  it('域名沿用缓存里试通的那个:这一档换的是格式,不是域名', async () => {
    const cache = createComboCache()
    cache.remember('read.php', { format: 'json', host: 'https://ngabbs.com' })
    const { transport, requests } = bothWork()
    const fetchNga = createNgaFetcher({
      transport,
      comboCache: cache,
      strategies: [createWebFallbackStrategy({ placement: 'primary', getMode: () => 'only' })],
    })

    await fetchNga(READ)

    expect(requests[0]!.url).toContain('https://ngabbs.com/read.php')
    // 不带格式参数才是网页版
    expect(requests[0]!.url).not.toContain('__output=')
  })

  it('诊断记录里这一档的格式档位是 html,排障时一眼看得出走了哪条路', async () => {
    const { transport } = fakeTransport(() => ({ body: BLOCKED, contentType: 'text/html' }))
    const fetchNga = createNgaFetcher({
      transport,
      strategies: [createWebFallbackStrategy({ placement: 'primary', getMode: () => 'only' })],
    })

    await expect(fetchNga(READ)).rejects.toMatchObject({
      diagnostic: { attempts: [{ strategy: 'web-fallback', format: 'html' }] },
    })
  })
})
