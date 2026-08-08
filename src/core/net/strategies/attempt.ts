import { buildAuthAttachment, type NgaCredentials } from '../auth'
import type { FetchCombo } from '../combo'
import { RESPONSE_FORMATS, X_USER_AGENT_VALUE, isJsonFormat, type UserAgentProfile } from '../constants'
import { decodeResponseBody } from '../encoding/decode-body'
import { parseNgaJson } from '../envelope'
import { NgaError } from '../errors'
import { buildQueryString, hasGbkParam, type QueryParams } from '../query'
import type { HttpTransport } from '../transport'
import type { FetchContext, NgaRequest, StrategyOutcome } from '../types'

/**
 * 链上所有直连策略共用的「发一次请求」。
 *
 * direct（单发）和 format-rotation（枚举组合连发）、switch-account（换凭证再发一次）
 * 只在**发几次、用哪个组合**上不同，拼 URL / 附认证 / 解码 / 失败分类这套完全一样，
 * 所以收在这里一份。失败分类决定链要不要往下走，见 errors.ts 的 defaultRetryable。
 */

export interface AttemptOptions {
  /** 记进结果与错误的策略名 */
  readonly via: string
  readonly combo: FetchCombo
  /** 覆盖凭证（换账号重试用）。`undefined` = 不覆盖，`null` = 强制游客 */
  readonly credentials?: NgaCredentials | null
  /** 覆盖传输层（每次重试前重建 HTTP client 用） */
  readonly transport?: HttpTransport
}

/**
 * 这次请求该用哪个 UA 档位。
 *
 * `read.php` 可切 Windows Phone UA（ADR-0002，实测更不容易被封）——是**策略开关**，
 * 由设备侧从设置里读，默认不开；单条请求显式写了 `userAgent` 的以它为准。
 */
export function resolveUserAgentProfile(
  request: NgaRequest,
  context: FetchContext,
): UserAgentProfile {
  if (request.userAgent !== undefined) return request.userAgent
  if (context.readPhpUserAgent !== undefined && request.path.startsWith('read.php')) {
    return context.readPhpUserAgent
  }
  return 'webview'
}

function buildUrl(request: NgaRequest, combo: FetchCombo, host: string): string {
  const query: QueryParams = {
    // 声明输入/输出用 UTF-8（MNGA 全局带）。但只要这次请求里有按 GBK 编码的参数，
    // 就不能再声明 UTF8——那是 Android 的全 GBK 路线，两者混着来服务端会解错参数。
    __inchst: hasGbkParam(request.query) ? null : 'UTF8',
    ...Object.fromEntries(RESPONSE_FORMATS[combo.format].params),
    ...request.query,
  }
  const queryString = buildQueryString(query)
  return queryString === '' ? `${host}/${request.path}` : `${host}/${request.path}?${queryString}`
}

function unavailable(message: string, via: string): StrategyOutcome {
  return { ok: false, error: new NgaError({ kind: 'unavailable', message, via }) }
}

/** 发一次请求并把结果分类。不抛异常，一切失败都走 StrategyOutcome。 */
export async function runAttempt(
  request: NgaRequest,
  context: FetchContext,
  options: AttemptOptions,
): Promise<StrategyOutcome> {
  const { via, combo } = options
  if (!isJsonFormat(combo.format)) {
    // XML / HTML 两条解析路线归 19 号票（Web 反解与网页兜底）。
    // 标成可重试，链上真有能处理这个格式的策略时才轮得到它。
    return unavailable(`${via} 只解析 JSON 家族格式，收到 ${combo.format}`, via)
  }

  const host = combo.host.replace(/\/+$/, '')
  const method = request.method ?? 'POST'
  const authMode = request.auth ?? context.authMode
  const credentials =
    options.credentials !== undefined
      ? options.credentials
      : request.credentials === undefined
        ? context.credentials
        : request.credentials
  const auth = buildAuthAttachment(authMode, credentials)
  if (method === 'GET' && Object.keys(auth.form).length > 0) {
    // form 方式把凭证放 POST body，GET 没有 body——静默降级成游客请求太难查了
    return unavailable("form 认证方式要求 POST，这条请求写的是 GET；改用 auth: 'cookie'", via)
  }

  const userAgent = resolveUserAgentProfile(request, context)
  const userAgentValue = context.userAgents[userAgent]
  const headers: Record<string, string> = {
    'User-Agent': userAgentValue,
    // 客户端身份放辅助头（Android v4 的现行做法，API 文档 §0.3）
    'X-User-Agent': X_USER_AGENT_VALUE,
    Referer: request.referer ?? `${host}/${request.refererPath ?? ''}`,
    ...auth.headers,
  }

  let body: string | undefined
  if (method === 'POST') {
    const formParams: QueryParams = { ...request.form, ...auth.form }
    body = buildQueryString(formParams)
    // 表单里有 GBK 值时要声明出来，否则服务端按 UTF-8 解 percent 字节（API 文档 §0.5）
    headers['Content-Type'] = hasGbkParam(request.form)
      ? 'application/x-www-form-urlencoded;charset=GBK'
      : 'application/x-www-form-urlencoded'
  }

  const report = (outcome: StrategyOutcome): StrategyOutcome => {
    context.onEvent?.({
      type: 'attempt',
      strategy: via,
      path: request.path,
      format: combo.format,
      host,
      userAgent,
      userAgentValue,
      uid: credentials?.uid ?? null,
      ...(outcome.ok ? {} : { error: outcome.error }),
    })
    return outcome
  }

  const transport = options.transport ?? context.transport
  let status: number
  let text: string
  try {
    const response = await transport({
      url: buildUrl(request, combo, host),
      method,
      headers,
      body,
      signal: request.signal,
    })
    status = response.status
    text = decodeResponseBody(response.body, response.contentType)
  } catch (cause) {
    // 调用方主动取消不是「被封」，别让链继续往下试
    const aborted =
      request.signal?.aborted === true || (cause instanceof Error && cause.name === 'AbortError')
    return report({
      ok: false,
      error: new NgaError({
        kind: 'network',
        message: cause instanceof Error ? cause.message : '网络请求失败',
        via,
        cause,
        retryable: !aborted,
      }),
    })
  }

  // HTTP 非 2xx 时 body 仍可能带有效错误信息，所以先解析 body，
  // **body 为空**才退回状态码报错（API 文档 §0.7）——
  // 非 2xx 但 body 有内容只是解析不了，那更像被封，要留 parse 这个信号。
  try {
    return report({ ok: true, result: { ...parseNgaJson(text, via), via } })
  } catch (cause) {
    if (cause instanceof NgaError && cause.kind === 'server') {
      return report({ ok: false, error: cause })
    }
    const statusFailed = status < 200 || status >= 300
    if (statusFailed && text.trim() === '') {
      return report({
        ok: false,
        error: new NgaError({ kind: 'http', status, message: `HTTP ${status}`, via, cause }),
      })
    }
    return report({
      ok: false,
      error: new NgaError({
        kind: 'parse',
        message: cause instanceof NgaError ? cause.message : '响应解析失败',
        status,
        via,
        cause,
      }),
    })
  }
}
