import { buildAuthAttachment, type NgaCredentials } from '../auth'
import type { FetchCombo } from '../combo'
import { RESPONSE_FORMATS, X_USER_AGENT_VALUE, isJsonFormat, type UserAgentProfile } from '../constants'
import { decodeResponseBody } from '../encoding/decode-body'
import { parseNgaJson, type NgaEnvelope } from '../envelope'
import { NgaError, isAuthLevelServerError } from '../errors'
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
  /**
   * 覆盖响应解析（Web 反解档用：`html` 档要走 `core/net/web` 的反解器）。
   * 契约同 `parseNgaJson`：解不出来抛 `kind: 'parse'`，服务端语义错误抛 `kind: 'server'`。
   * 给了它就不再限制格式档位。
   */
  readonly parse?: (text: string) => NgaEnvelope
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
  if (options.parse === undefined && !isJsonFormat(combo.format)) {
    // 没自带解析器就只会解 JSON 家族；HTML 档由 Web 反解那一档自带解析器进来（19 票），
    // XML 档至今没有解析器。标成可重试，链上真有能处理这个格式的策略时才轮得到它。
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
  if (method === 'GET' && Object.keys(auth.form).length > 0 && authMode === 'form') {
    // form 方式把凭证放 POST body，GET 没有 body——静默降级成游客请求太难查了。
    // `both` 档不用报错：GET 时 form 那一半带不上，但 Cookie 头还在
    return unavailable("form 认证方式要求 POST，这条请求写的是 GET；改用 auth: 'both'", via)
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
  const parse = options.parse ?? ((body: string) => parseNgaJson(body, via, request.envelope))
  try {
    const envelope = parse(text)
    // 调用方的一票否决（`NgaRequest.validate`）：形状不对的响应等同于解析失败，
    // 于是它既不会被当成结果交出去，也不会被 format-rotation 记成「好组合」
    const rejected = request.validate?.(envelope)
    if (rejected !== undefined) {
      throw new NgaError({ kind: 'parse', message: rejected, status, via })
    }
    return report({ ok: true, result: { ...envelope, via } })
  } catch (cause) {
    if (cause instanceof NgaError && cause.kind === 'server') {
      // 服务端说「未登录」而我们手上明明有凭证 = 这一发的身份没送到，不是语义错误。
      // 标成可重试，让 format-rotation 换下一个组合（判据见 AUTH_LEVEL_SERVER_MESSAGES）。
      // 顺带避开 rotation 里「服务端语义错误 = 这个组合是通的」那条缓存规则——
      // 否则丢身份的那个域名会被记住并继续用满一个缓存周期（2026-08-13 真机取证）。
      //
      // 写操作（签到 / 点赞 / 回帖）也走这条路，但不会重复提交：服务端回「未登录」
      // 就是它**拒绝**了这一发，没有副作用可言，换个域名重发才是用户要的结果。
      //
      // 游客态也要标成可重试——不是为了换域名（游客哪个域名都没 cookie，换了也白换，
      // 那一层在 format-rotation 里单独刹住），而是为了**别把整条链掐死**：
      // 链上后面还有网页兜底和帖子缓存，它们确实能把这一页拿出来（2026-08-13 真机取证：
      // 同一个帖子接口报未登录、点「用网页版打开」正文完整渲染）。
      if (isAuthLevelServerError(cause.message)) {
        return report({
          ok: false,
          error: new NgaError({
            kind: 'server',
            message: cause.message,
            ...(cause.code === undefined ? {} : { code: cause.code }),
            via,
            retryable: true,
            cause,
          }),
        })
      }
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
