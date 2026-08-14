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

/**
 * 「未登录」这一档单独测：它长得像服务端语义错误，实际是这一发请求没带上身份。
 * 真机取证（2026-08-13，小米 25113PN0EC）：冷启动后第一个版块约 1/6 概率直接
 * 报「1:未登录」，手点重试（= 忘掉组合重来）立刻就好——因为 okhttp 的 cookie jar
 * 按域名存，换个域名我们自己拼的 Cookie 头就不会被顶掉（见 auth.ts 文件头）。
 */
describe('createFormatRotationStrategy · 服务端说「未登录」', () => {
  const UNAUTHED = utf8('{"error":{"code":1,"0":"未登录"},"time":1}')
  const CREDENTIALS = { uid: '10000001', token: 'cid-a' }

  /** 只有 `only` 这个组合认得出身份，其余一律回「未登录」。 */
  function onlyAuthed(only: string) {
    return fakeTransport((combo) => (combo === only ? { body: OK } : { body: UNAUTHED }))
  }

  it('手上有凭证时继续换组合,直到某个域名认出身份', async () => {
    const { transport, combos } = onlyAuthed('lite=js@https://ngabbs.com')
    const fetchNga = createNgaFetcher({
      transport,
      host: HOSTS[0],
      getCredentials: () => CREDENTIALS,
      strategies: [createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 })],
    })

    const result = await fetchNga({ path: 'thread.php', query: { fid: 650 } })

    expect(result.via).toBe('format-rotation')
    expect(combos).toEqual([
      '__output=8@https://bbs.nga.cn',
      'lite=js@https://bbs.nga.cn',
      '__output=8@https://ngabbs.com',
      'lite=js@https://ngabbs.com',
    ])
  })

  it('丢身份的那个组合不会被记进缓存,下一次不从它开局', async () => {
    const cache = createComboCache()
    const { transport, combos } = onlyAuthed('lite=js@https://ngabbs.com')
    const fetchNga = createNgaFetcher({
      transport,
      comboCache: cache,
      host: HOSTS[0],
      getCredentials: () => CREDENTIALS,
      strategies: [createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 })],
    })
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

  it('游客态不换组合:哪个域名都没 cookie,白跑一整轮只会把错误页拖慢', async () => {
    const { transport, combos } = onlyAuthed('lite=js@https://ngabbs.com')
    const fetchNga = createNgaFetcher({
      transport,
      host: HOSTS[0],
      getCredentials: () => null,
      strategies: [createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 })],
    })

    await expect(fetchNga({ path: 'thread.php', query: { fid: 650 } })).rejects.toMatchObject({
      kind: 'server',
      message: '未登录',
    })
    expect(combos).toEqual(['__output=8@https://bbs.nga.cn'])
  })

  /**
   * 但「不换组合」不等于「掐死整条链」。真机取证（2026-08-13）：游客态打开帖子，
   * 直连报未登录，而点「用网页版打开」正文完整渲染——网页兜底本来就能拿到这一页，
   * 只是以前错误被判成不可重试，`runStrategyChain` 在轮到它之前就抛了。
   */
  it('游客态的未登录仍然可重试,好让链上后面的网页兜底接手', async () => {
    const { transport, combos } = onlyAuthed('(没有能用的组合)')
    const rescue = {
      name: 'web-fallback',
      run: () =>
        Promise.resolve({
          ok: true as const,
          result: { root: { data: { rescued: true } }, data: { rescued: true }, via: 'web-fallback' },
        }),
    }
    const fetchNga = createNgaFetcher({
      transport,
      host: HOSTS[0],
      getCredentials: () => null,
      strategies: [
        createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 }),
        rescue,
      ],
    })

    const result = await fetchNga({ path: 'read.php', query: { tid: 1 } })

    expect(result.via).toBe('web-fallback')
    // 只发了一次直连就让位给兜底,没有白跑一整轮组合
    expect(combos).toEqual(['__output=8@https://bbs.nga.cn'])
  })

  it('所有组合都说未登录时,报给用户的仍是服务端原话', async () => {
    const { transport, combos } = onlyAuthed('(没有能用的组合)')
    const fetchNga = createNgaFetcher({
      transport,
      host: HOSTS[0],
      getCredentials: () => CREDENTIALS,
      strategies: [createFormatRotationStrategy({ formats: FORMATS, hosts: HOSTS, maxAttempts: 10 })],
    })

    await expect(fetchNga({ path: 'thread.php', query: { fid: 650 } })).rejects.toMatchObject({
      kind: 'server',
      message: '未登录',
    })
    expect(combos).toHaveLength(4)
  })
})
