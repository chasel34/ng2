import { describe, expect, it, vi } from 'vitest'
import { fixtureContentType, readFixtureBytes, type NetFixtureName } from './__fixtures__'
import { createNgaFetcher, runStrategyChain } from './fetcher'
import { NgaError } from './errors'
import { gbk } from './query'
import { createDirectStrategy } from './strategies/direct'
import type { HttpRequest, HttpResponse, HttpTransport } from './transport'
import type { FetchContext, FetchStrategy, NgaRequest } from './types'

const utf8 = (text: string) => new TextEncoder().encode(text)

/** 记录收到的请求，按脚本返回响应。 */
function fakeTransport(
  respond: (request: HttpRequest) => Partial<HttpResponse> | Promise<Partial<HttpResponse>>,
): { transport: HttpTransport; requests: HttpRequest[] } {
  const requests: HttpRequest[] = []
  const transport: HttpTransport = async (request) => {
    requests.push(request)
    const response = await respond(request)
    return {
      status: response.status ?? 200,
      contentType: response.contentType ?? 'text/javascript; charset=GBK',
      body: response.body ?? utf8('{"data":{"0":"ok"},"time":1}'),
    }
  }
  return { transport, requests }
}

function fixtureResponse(name: NetFixtureName): Partial<HttpResponse> {
  return { body: readFixtureBytes(name), contentType: fixtureContentType(name) }
}

describe('createNgaFetcher · 请求拼装', () => {
  it('自动带公共参数、格式参数，并剔除空值参数', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await fetchNga({ path: 'thread.php', query: { fid: 650, stid: null, page: 1 } })

    const url = new URL(requests[0]!.url)
    expect(url.origin + url.pathname).toBe('https://bbs.nga.cn/thread.php')
    expect(url.searchParams.get('__inchst')).toBe('UTF8')
    expect(url.searchParams.get('__output')).toBe('8')
    expect(url.searchParams.get('fid')).toBe('650')
    expect(url.searchParams.has('stid')).toBe(false)
  })

  it('按 format 换格式参数（反封锁链交替的就是这一维）', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await fetchNga({ path: 'nuke.php', format: 'jsonLite', query: { __lib: 'noti' } })

    expect(requests[0]!.url).toContain('lite=js')
    expect(requests[0]!.url).not.toContain('__output=')
  })

  it('GBK 参数按 GBK 编码进 query，并撤掉 __inchst=UTF8', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await fetchNga({ path: 'thread.php', query: { author: gbk('原神') } })
    await fetchNga({ path: 'thread.php', query: { key: '原神' } })

    // 同一个 thread.php，author 是 GBK 而 key 是 UTF-8（API 文档 §0.5）
    expect(requests[0]!.url).toContain('author=%D4%AD%C9%F1')
    expect(requests[0]!.url).not.toContain('__inchst')
    expect(requests[1]!.url).toContain('key=%E5%8E%9F%E7%A5%9E')
    expect(requests[1]!.url).toContain('__inchst=UTF8')
  })

  it('表单里有 GBK 值时声明 charset=GBK', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await fetchNga({ path: 'nuke.php', form: { content: gbk('原神') } })

    expect(requests[0]!.headers['Content-Type']).toBe(
      'application/x-www-form-urlencoded;charset=GBK',
    )
    expect(requests[0]!.body).toBe('content=%D4%AD%C9%F1')
  })

  it('form 认证配 GET 会明确报错，不静默降级成游客', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({
      transport,
      authMode: 'form',
      getCredentials: () => ({ uid: '10000001', token: 'fake-token' }),
    })

    await expect(
      fetchNga({ path: 'thread.php', method: 'GET' }),
    ).rejects.toMatchObject({ kind: 'unavailable' })
    expect(requests).toHaveLength(0)
  })

  it('cookie 认证配 GET 正常', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({
      transport,
      authMode: 'cookie',
      getCredentials: () => ({ uid: '10000001', token: 'fake-token' }),
    })

    await fetchNga({ path: 'thread.php', method: 'GET' })

    expect(requests[0]!.method).toBe('GET')
    expect(requests[0]!.body).toBeUndefined()
    expect(requests[0]!.headers.Cookie).toContain('ngaPassportUid=10000001')
  })

  it('带 UA 身份头与 Referer', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport, webViewUserAgent: 'SystemWebView/1.0' })

    await fetchNga({ path: 'nuke.php' })

    expect(requests[0]!.headers['User-Agent']).toBe('SystemWebView/1.0')
    expect(requests[0]!.headers['X-User-Agent']).toBe('Nga_Official')
    expect(requests[0]!.headers.Referer).toBe('https://bbs.nga.cn/')
  })

  it('read.php 可切 Windows Phone UA', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await fetchNga({ path: 'read.php', userAgent: 'windowsPhone' })

    expect(requests[0]!.headers['User-Agent']).toBe('NGA_WP_JW/(;WINDOWS)')
  })

  it('默认 POST，业务参数在 query、认证在 body', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({
      transport,
      authMode: 'form',
      getCredentials: () => ({ uid: '10000001', token: 'fake-token' }),
    })

    await fetchNga({ path: 'thread.php', query: { fid: 650 } })

    expect(requests[0]!.method).toBe('POST')
    expect(requests[0]!.body).toBe('access_uid=10000001&access_token=fake-token')
    expect(requests[0]!.headers['Content-Type']).toBe('application/x-www-form-urlencoded')
  })

  it('cookie 认证方式把凭证放头里，body 不带凭证', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({
      transport,
      authMode: 'cookie',
      getCredentials: () => ({ uid: '10000001', token: 'fake-token' }),
    })

    await fetchNga({ path: 'nuke.php' })

    expect(requests[0]!.headers.Cookie).toBe(
      'ngaPassportUid=10000001; ngaPassportCid=fake-token',
    )
    expect(requests[0]!.body).toBe('')
  })

  it('单条请求可覆盖账号（反封锁链换账号重试要用）', async () => {
    const { transport, requests } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({
      transport,
      authMode: 'cookie',
      getCredentials: () => ({ uid: '1', token: 'a' }),
    })

    await fetchNga({ path: 'nuke.php', credentials: { uid: '2', token: 'b' } })
    await fetchNga({ path: 'nuke.php', credentials: null })

    expect(requests[0]!.headers.Cookie).toContain('ngaPassportUid=2')
    expect(requests[1]!.headers.Cookie).toBeUndefined()
  })
})

describe('createNgaFetcher · 响应处理', () => {
  it('真实抓包：通知接口解出 data', async () => {
    const { transport } = fakeTransport(() => fixtureResponse('notiEmpty'))
    const fetchNga = createNgaFetcher({ transport })

    const result = await fetchNga({
      path: 'nuke.php',
      query: { __lib: 'noti', __act: 'get_all' },
    })

    expect(result.via).toBe('direct')
    expect(result.data).toEqual({ '0': '' })
  })

  it('真实抓包：未声明 charset 的 GBK 主题列表照样解出中文', async () => {
    const { transport } = fakeTransport(() => fixtureResponse('threadList'))
    const fetchNga = createNgaFetcher({ transport })

    const result = await fetchNga({ path: 'thread.php', query: { fid: 650 } })
    const data = result.data as Record<string, Record<string, unknown>>

    expect(data.__F!.name).toBe('原神')
  })

  it('HTTP 非 2xx 时先解析 body，body 有错误信息就报服务端错误', async () => {
    const { transport } = fakeTransport(() => ({
      status: 403,
      ...fixtureResponse('readThreadNotFound'),
    }))
    const fetchNga = createNgaFetcher({ transport })

    await expect(fetchNga({ path: 'read.php', query: { tid: 1 } })).rejects.toMatchObject({
      kind: 'server',
      message: expect.stringContaining('找不到主题'),
    })
  })

  it('HTTP 非 2xx 且 body 有正常数据时，仍然当成功', async () => {
    const { transport } = fakeTransport(() => ({ status: 500, ...fixtureResponse('ucpUser') }))
    const fetchNga = createNgaFetcher({ transport })

    const result = await fetchNga({ path: 'nuke.php' })
    expect((result.data as Record<string, Record<string, unknown>>)['0']!.username).toBe('BugenZhao')
  })

  it('HTTP 非 2xx 且 body 为空才用状态码报错', async () => {
    const { transport } = fakeTransport(() => ({
      status: 502,
      body: utf8(''),
      contentType: 'text/html',
    }))
    const fetchNga = createNgaFetcher({ transport })

    await expect(fetchNga({ path: 'nuke.php' })).rejects.toMatchObject({
      kind: 'http',
      status: 502,
      retryable: true,
    })
  })

  it('非 2xx 但 body 有内容只是解析不了 → parse（被封的信号），状态码一并带上', async () => {
    const { transport } = fakeTransport(() => ({
      status: 403,
      body: utf8('<html>Forbidden</html>'),
      contentType: 'text/html',
    }))
    const fetchNga = createNgaFetcher({ transport })

    await expect(fetchNga({ path: 'nuke.php' })).rejects.toMatchObject({
      kind: 'parse',
      status: 403,
      retryable: true,
    })
  })

  it('假错误当成功，data 为空由调用方判断', async () => {
    const { transport } = fakeTransport(() => fixtureResponse('ucpNotFound'))
    const fetchNga = createNgaFetcher({ transport })

    const result = await fetchNga({ path: 'nuke.php', query: { __lib: 'ucp' } })
    expect(result.fakeError?.message).toBe('找不到用户')
    expect(result.data).toBeUndefined()
  })

  it('传输层异常归为 network 错误', async () => {
    const transport: HttpTransport = () => Promise.reject(new Error('连接超时'))
    const fetchNga = createNgaFetcher({ transport })

    await expect(fetchNga({ path: 'nuke.php' })).rejects.toMatchObject({
      kind: 'network',
      retryable: true,
    })
  })

  it('调用方取消不算被封，不触发后面的兜底', async () => {
    const controller = new AbortController()
    const transport: HttpTransport = () => {
      controller.abort()
      return Promise.reject(new DOMException('Aborted', 'AbortError'))
    }
    const fetchNga = createNgaFetcher({ transport })

    await expect(
      fetchNga({ path: 'nuke.php', signal: controller.signal }),
    ).rejects.toMatchObject({ kind: 'network', retryable: false })
  })

  it('XML / HTML 格式暂不由 direct 解析，给出明确错误', async () => {
    const { transport } = fakeTransport(() => ({}))
    const fetchNga = createNgaFetcher({ transport })

    await expect(fetchNga({ path: 'read.php', format: 'xml' })).rejects.toMatchObject({
      kind: 'unavailable',
    })
  })
})

describe('runStrategyChain · 反封锁链框架（ADR-0002）', () => {
  const context = {
    transport: fakeTransport(() => ({})).transport,
    host: 'https://bbs.nga.cn',
    authMode: 'none',
    credentials: null,
    userAgents: {
      official: 'o',
      webview: 'w',
      windowsPhone: 'p',
      desktop: 'd',
    },
  } satisfies FetchContext

  const request: NgaRequest = { path: 'thread.php' }

  function stubStrategy(name: string, outcome: 'ok' | NgaError): FetchStrategy {
    return {
      name,
      run: vi.fn(async () =>
        outcome === 'ok'
          ? ({
              ok: true,
              result: { root: {}, data: { from: name }, via: name },
            } as const)
          : ({ ok: false, error: outcome } as const),
      ),
    }
  }

  it('第一个成功的策略产出结果，后面的不跑', async () => {
    const first = stubStrategy('first', 'ok')
    const second = stubStrategy('second', 'ok')

    const result = await runStrategyChain([first, second], request, context)

    expect(result.via).toBe('first')
    expect(second.run).not.toHaveBeenCalled()
  })

  it('可重试的失败（解析失败 ≈ 被封）会落到下一档', async () => {
    const blocked = stubStrategy(
      'direct',
      new NgaError({ kind: 'parse', message: '解析失败' }),
    )
    const fallback = stubStrategy('cache', 'ok')

    const result = await runStrategyChain([blocked, fallback], request, context)

    expect(result.via).toBe('cache')
    expect(fallback.run).toHaveBeenCalledOnce()
  })

  it('服务端语义错误不重试，立刻抛出，后面的兜底不浪费', async () => {
    const semantic = stubStrategy(
      'direct',
      new NgaError({ kind: 'server', message: '找不到主题' }),
    )
    const fallback = stubStrategy('cache', 'ok')

    await expect(runStrategyChain([semantic, fallback], request, context)).rejects.toMatchObject({
      kind: 'server',
    })
    expect(fallback.run).not.toHaveBeenCalled()
  })

  it('全链失败时抛最后一个错误', async () => {
    const chain = [
      stubStrategy('a', new NgaError({ kind: 'parse', message: '甲' })),
      stubStrategy('b', new NgaError({ kind: 'network', message: '乙' })),
    ]

    await expect(runStrategyChain(chain, request, context)).rejects.toThrowError('乙')
  })

  it('空链直接报错', async () => {
    await expect(runStrategyChain([], request, context)).rejects.toMatchObject({
      kind: 'unavailable',
    })
  })

  it('每一档的开始/成功/失败都发事件，方便排障', async () => {
    const events: string[] = []
    const chain = [
      stubStrategy('direct', new NgaError({ kind: 'parse', message: 'x' })),
      stubStrategy('cache', 'ok'),
    ]

    await runStrategyChain(chain, request, {
      ...context,
      onEvent: (e) => {
        if ('strategy' in e) events.push(`${e.type}:${e.strategy}`)
      },
    })

    expect(events).toEqual([
      'strategy-start:direct',
      'strategy-failure:direct',
      'strategy-start:cache',
      'strategy-success:cache',
    ])
  })

  it('direct 策略可以和别的策略一起排进链里', async () => {
    const { transport } = fakeTransport(() => fixtureResponse('notiEmpty'))
    const fetchNga = createNgaFetcher({
      transport,
      strategies: [createDirectStrategy(), stubStrategy('cache', 'ok')],
    })

    const result = await fetchNga({ path: 'nuke.php' })
    expect(result.via).toBe('direct')
  })
})
