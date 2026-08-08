import { buildAuthAttachment } from '../auth'
import {
  RESPONSE_FORMATS,
  X_USER_AGENT_VALUE,
  isJsonFormat,
  type ResponseFormat,
} from '../constants'
import { decodeResponseBody } from '../encoding/decode-body'
import { parseNgaJson } from '../envelope'
import { NgaError } from '../errors'
import { buildQueryString, hasGbkParam, type QueryParams } from '../query'
import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'

export const DIRECT_STRATEGY_NAME = 'direct'

function buildUrl(request: NgaRequest, host: string, format: ResponseFormat): string {
  const query: QueryParams = {
    // 声明输入/输出用 UTF-8（MNGA 全局带）。但只要这次请求里有按 GBK 编码的参数，
    // 就不能再声明 UTF8——那是 Android 的全 GBK 路线，两者混着来服务端会解错参数。
    __inchst: hasGbkParam(request.query) ? null : 'UTF8',
    ...Object.fromEntries(RESPONSE_FORMATS[format].params),
    ...request.query,
  }
  const queryString = buildQueryString(query)
  return queryString === '' ? `${host}/${request.path}` : `${host}/${request.path}?${queryString}`
}

function unavailable(message: string): StrategyOutcome {
  return {
    ok: false,
    error: new NgaError({ kind: 'unavailable', message, via: DIRECT_STRATEGY_NAME }),
  }
}

/**
 * 反封锁链的第一档：直连官方域名发一次请求。
 *
 * 失败分类决定链要不要往下走：解析失败/HTTP 状态错误 ≈ 被封（可重试），
 * 服务端明确的语义错误（找不到主题之类）不重试，直接抛给调用方。
 */
export function createDirectStrategy(): FetchStrategy {
  return {
    name: DIRECT_STRATEGY_NAME,
    async run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome> {
      const via = DIRECT_STRATEGY_NAME
      const format = request.format ?? 'json'
      if (!isJsonFormat(format)) {
        // XML / HTML 两条解析路线归 ticket 18、19（Web 反解与网页兜底），
        // 到时按 FetchStrategy 接口另加策略，不改这里。
        // 标成可重试，链上真有能处理这个格式的策略时才轮得到它。
        return unavailable(`direct 策略只解析 JSON 家族格式，收到 ${format}`)
      }

      const host = (request.host ?? context.host).replace(/\/+$/, '')
      const method = request.method ?? 'POST'
      const authMode = request.auth ?? context.authMode
      const auth = buildAuthAttachment(
        authMode,
        request.credentials === undefined ? context.credentials : request.credentials,
      )
      if (method === 'GET' && Object.keys(auth.form).length > 0) {
        // form 方式把凭证放 POST body，GET 没有 body——静默降级成游客请求太难查了
        return unavailable("form 认证方式要求 POST，这条请求写的是 GET；改用 auth: 'cookie'")
      }

      const headers: Record<string, string> = {
        'User-Agent': context.userAgents[request.userAgent ?? 'webview'],
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

      let status: number
      let text: string
      try {
        const response = await context.transport({
          url: buildUrl(request, host, format),
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
          request.signal?.aborted === true ||
          (cause instanceof Error && cause.name === 'AbortError')
        return {
          ok: false,
          error: new NgaError({
            kind: 'network',
            message: cause instanceof Error ? cause.message : '网络请求失败',
            via,
            cause,
            retryable: !aborted,
          }),
        }
      }

      // HTTP 非 2xx 时 body 仍可能带有效错误信息，所以先解析 body，
      // **body 为空**才退回状态码报错（API 文档 §0.7）——
      // 非 2xx 但 body 有内容只是解析不了，那更像被封，要留 parse 这个信号。
      try {
        return { ok: true, result: { ...parseNgaJson(text, via), via } }
      } catch (cause) {
        if (cause instanceof NgaError && cause.kind === 'server') {
          return { ok: false, error: cause }
        }
        const statusFailed = status < 200 || status >= 300
        if (statusFailed && text.trim() === '') {
          return {
            ok: false,
            error: new NgaError({ kind: 'http', status, message: `HTTP ${status}`, via, cause }),
          }
        }
        return {
          ok: false,
          error: new NgaError({
            kind: 'parse',
            message: cause instanceof NgaError ? cause.message : '响应解析失败',
            status,
            via,
            cause,
          }),
        }
      }
    },
  }
}
