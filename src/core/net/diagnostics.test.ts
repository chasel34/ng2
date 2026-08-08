import { describe, expect, it } from 'vitest'
import {
  appendDiagnosticLog,
  describeFetchFailure,
  diagnosticSummary,
  formatDiagnostic,
  type FetchDiagnostic,
} from './diagnostics'
import { createNgaFetcher } from './fetcher'
import { createFormatRotationStrategy } from './strategies/format-rotation'
import type { HttpResponse, HttpTransport } from './transport'

const utf8 = (text: string) => new TextEncoder().encode(text)
const BLOCKED = utf8('<html><body>403 Forbidden</body></html>')

function blockedTransport(): HttpTransport {
  return () =>
    Promise.resolve<HttpResponse>({ status: 403, contentType: 'text/html', body: BLOCKED })
}

const SAMPLE: FetchDiagnostic = {
  at: Date.UTC(2026, 7, 8, 12, 30),
  path: 'read.php',
  params: { tid: '42800000', page: '1' },
  message: '响应解析失败',
  attempts: [
    {
      strategy: 'format-rotation',
      format: 'json',
      host: 'https://bbs.nga.cn',
      userAgent: 'windowsPhone',
      userAgentValue: 'NGA_WP_JW/(;WINDOWS)',
      uid: '10000001',
      error: { kind: 'parse', message: '响应不是合法 JSON', status: 403 },
    },
    {
      strategy: 'switch-account',
      format: 'jsonLite',
      host: 'https://ngabbs.com',
      userAgent: 'windowsPhone',
      userAgentValue: 'NGA_WP_JW/(;WINDOWS)',
      uid: '10000002',
      error: { kind: 'network', message: '连接超时' },
    },
  ],
}

describe('diagnosticSummary · 错误页上那一行', () => {
  it('业务参数 + UA 档位,照设计稿 `tid=… · page=1 · ua=…`', () => {
    expect(diagnosticSummary(SAMPLE)).toBe('tid=42800000 · page=1 · ua=windowsPhone')
  })

  it('一次请求都没发出去时只剩参数', () => {
    expect(diagnosticSummary({ ...SAMPLE, attempts: [] })).toBe('tid=42800000 · page=1')
  })
})

describe('formatDiagnostic · 落本地日志的文本', () => {
  it('首行是请求与最终错误,其后每行一次尝试(组合/UA/账号/结果)', () => {
    const lines = formatDiagnostic(SAMPLE).split('\n')

    expect(lines[0]).toBe('2026-08-08T12:30:00.000Z read.php?tid=42800000&page=1 失败：响应解析失败')
    expect(lines[1]).toBe(
      '  1. [format-rotation] json @ https://bbs.nga.cn ua=windowsPhone uid=10000001 → parse 403: 响应不是合法 JSON',
    )
    expect(lines[2]).toBe(
      '  2. [switch-account] jsonLite @ https://ngabbs.com ua=windowsPhone uid=10000002 → network: 连接超时',
    )
  })

  it('游客请求标成游客,不留空', () => {
    const guest = formatDiagnostic({
      ...SAMPLE,
      attempts: [{ ...SAMPLE.attempts[0]!, uid: null }],
    })
    expect(guest).toContain('ua=windowsPhone 游客 →')
  })
})

describe('describeFetchFailure · 错误页上的两行说明', () => {
  it('带状态码的失败照设计稿说「服务端返回 HTTP …」', () => {
    expect(describeFetchFailure({ kind: 'parse', status: 403, message: 'x' })).toEqual({
      headline: '服务端返回 HTTP 403',
      hint: '第三方客户端被拦是最常见的原因，可以先用网页版打开',
    })
  })

  it('没有状态码的解析失败也要说得出是什么事', () => {
    expect(describeFetchFailure({ kind: 'parse', message: 'x' }).headline).toBe('响应内容解析不了')
  })

  it('服务端语义错误照搬原话:它比我们编的准', () => {
    expect(
      describeFetchFailure({ kind: 'server', message: '您没有浏览该版面的权限' }).headline,
    ).toBe('您没有浏览该版面的权限')
  })

  it('断网说断网,别引到「重新登录」上去', () => {
    expect(describeFetchFailure({ kind: 'network', message: '连接超时' })).toEqual({
      headline: '连不上服务器',
      hint: '检查网络连接后重试',
    })
  })
})

describe('appendDiagnosticLog · 本地日志上限', () => {
  it('最新的追在最后,超出上限时从最旧的开始丢', () => {
    let log: readonly string[] = []
    for (let i = 0; i < 5; i += 1) {
      log = appendDiagnosticLog(log, { ...SAMPLE, message: `第 ${i} 条` }, 3)
    }

    expect(log).toHaveLength(3)
    expect(log[0]).toContain('第 2 条')
    expect(log[2]).toContain('第 4 条')
  })
})

describe('反封锁链失败时的诊断记录', () => {
  const fetcherWith = (onDiagnostic: (d: FetchDiagnostic) => void) =>
    createNgaFetcher({
      transport: blockedTransport(),
      onDiagnostic,
      strategies: [
        createFormatRotationStrategy({
          formats: ['json', 'jsonLite'],
          hosts: ['https://bbs.nga.cn'],
        }),
      ],
    })

  it('全链失败时把试过的组合交给日志,并挂到抛出的错误上', async () => {
    const logged: FetchDiagnostic[] = []
    const fetchNga = fetcherWith((d) => logged.push(d))

    const error = await fetchNga({ path: 'read.php', query: { tid: 42800000, page: 1 } }).catch(
      (cause: unknown) => cause,
    )

    expect(logged).toHaveLength(1)
    const diagnostic = logged[0]!
    expect(diagnostic.path).toBe('read.php')
    expect(diagnostic.params).toEqual({ tid: '42800000', page: '1' })
    expect(diagnostic.attempts.map((a) => `${a.format}@${a.host}`)).toEqual([
      'json@https://bbs.nga.cn',
      'jsonLite@https://bbs.nga.cn',
    ])
    expect(diagnostic.attempts.every((a) => a.error?.kind === 'parse')).toBe(true)
    // 错误页直接从错误上取,不必再去日志里捞
    expect((error as { diagnostic?: FetchDiagnostic }).diagnostic).toBe(diagnostic)
  })

  it('自己拼的框架参数不进诊断,排障只关心业务参数', async () => {
    const logged: FetchDiagnostic[] = []
    await fetcherWith((d) => logged.push(d))({
      path: 'nuke.php',
      query: { __lib: 'noti', __act: 'get_all', page: 2 },
    }).catch(() => undefined)

    expect(logged[0]!.params).toEqual({ page: '2' })
  })

  it('成功的请求不写日志', async () => {
    const logged: FetchDiagnostic[] = []
    const fetchNga = createNgaFetcher({
      transport: () =>
        Promise.resolve<HttpResponse>({
          status: 200,
          contentType: 'text/javascript; charset=UTF-8',
          body: utf8('{"data":{"0":"ok"}}'),
        }),
      onDiagnostic: (d) => logged.push(d),
    })

    await fetchNga({ path: 'thread.php' })

    expect(logged).toHaveLength(0)
  })

  it('read.php 的 Windows Phone UA 是策略开关:默认关,开了才切', async () => {
    const agents: (string | undefined)[] = []
    const transport: HttpTransport = (request) => {
      agents.push(request.headers['User-Agent'])
      return Promise.resolve<HttpResponse>({
        status: 200,
        contentType: 'text/javascript; charset=UTF-8',
        body: utf8('{"data":{"0":"ok"}}'),
      })
    }

    await createNgaFetcher({ transport, webViewUserAgent: 'SystemWebView/1.0' })({
      path: 'read.php',
    })
    await createNgaFetcher({
      transport,
      webViewUserAgent: 'SystemWebView/1.0',
      getReadPhpUserAgent: () => 'windowsPhone',
    })({ path: 'read.php' })
    // 开关只管 read.php,别的接口照旧
    await createNgaFetcher({
      transport,
      webViewUserAgent: 'SystemWebView/1.0',
      getReadPhpUserAgent: () => 'windowsPhone',
    })({ path: 'thread.php' })

    expect(agents).toEqual(['SystemWebView/1.0', 'NGA_WP_JW/(;WINDOWS)', 'SystemWebView/1.0'])
  })
})
