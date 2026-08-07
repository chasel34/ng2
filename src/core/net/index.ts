/**
 * core/net —— 向 NGA 发请求并拿回洗干净的结构化数据。
 * 纯 TS，零 RN 依赖；HTTP 传输层由外部注入。
 *
 * 典型用法（core/api 各服务在此之上写字段遍历）：
 *
 * ```ts
 * const fetchNga = createNgaFetcher({
 *   transport: createFetchTransport(fetch),      // 设备侧传 expo/fetch
 *   getCredentials: () => currentAccount,
 * })
 * const { data } = await fetchNga({
 *   path: 'nuke.php',
 *   query: { __lib: 'noti', __act: 'get_all' },
 * })
 * ```
 */

export { buildAuthAttachment, type AuthAttachment, type AuthMode, type NgaCredentials } from './auth'
export {
  DEFAULT_NGA_HOST,
  FAKE_ERROR_MESSAGES,
  JSON_FORMATS,
  NGA_HOSTS,
  RESPONSE_FORMATS,
  USER_AGENT_PROFILES,
  X_USER_AGENT,
  type ResponseFormat,
  type UserAgentProfile,
} from './constants'
export { decodeResponseBody, parseCharset } from './encoding/decode-body'
export { decodeGb18030, gbkEncodeURIComponent } from './encoding/gb18030'
export { parseNgaJson, type NgaEnvelope } from './envelope'
export {
  NgaError,
  extractServerError,
  isFakeError,
  type NgaErrorKind,
  type NgaServerError,
} from './errors'
export {
  createNgaFetcher,
  runStrategyChain,
  type NgaFetcher,
  type NgaFetcherOptions,
} from './fetcher'
export { buildFormBody, buildQueryString, gbk, type GbkParam, type QueryParams, type QueryValue } from './query'
export { sanitizeNgaJson } from './sanitize'
export { DIRECT_STRATEGY_NAME, createDirectStrategy } from './strategies/direct'
export {
  createFetchTransport,
  type HttpRequest,
  type HttpResponse,
  type HttpTransport,
} from './transport'
export type {
  FetchContext,
  FetchEvent,
  FetchStrategy,
  NgaRequest,
  NgaResult,
  StrategyOutcome,
} from './types'
