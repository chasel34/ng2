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
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_ROTATION_FORMATS,
  createComboCache,
  enumerateCombos,
  formatParamsOf,
  interfaceKeyOf,
  isRotatableFormat,
  type ComboCache,
  type FetchCombo,
} from './combo'
export {
  DEFAULT_NGA_HOST,
  FAKE_ERROR_MESSAGES,
  NGA_HOSTS,
  RESPONSE_FORMATS,
  USER_AGENT_PROFILES,
  X_USER_AGENT_VALUE,
  isJsonFormat,
  type ResponseFormat,
  type UserAgentProfile,
} from './constants'
export {
  DIAGNOSTIC_LOG_LIMIT,
  appendDiagnosticLog,
  describeFetchFailure,
  diagnosticSummary,
  formatDiagnostic,
  type FetchAttemptError,
  type FetchFailureCopy,
  type FetchAttemptLog,
  type FetchDiagnostic,
} from './diagnostics'
export { decodeResponseBody } from './encoding/decode-body'
export { decodeGb18030, gbkEncodeURIComponent } from './encoding/gb18030'
export { parseNgaJson, type NgaEnvelope } from './envelope'
export { isRecord } from './is-record'
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
export {
  buildQueryString,
  gbk,
  type GbkParam,
  type QueryParams,
  type QueryValue,
} from './query'
export { sanitizeNgaJson } from './sanitize'
export { stripServerHtml } from './server-text'
export { resolveUserAgentProfile, runAttempt, type AttemptOptions } from './strategies/attempt'
export { DIRECT_STRATEGY_NAME, createDirectStrategy } from './strategies/direct'
export {
  FORMAT_ROTATION_STRATEGY_NAME,
  createFormatRotationStrategy,
  type FormatRotationOptions,
} from './strategies/format-rotation'
export {
  SWITCH_ACCOUNT_STRATEGY_NAME,
  createSwitchAccountStrategy,
  nextCredentialsAfter,
  type SwitchAccountOptions,
} from './strategies/switch-account'
export {
  TOPIC_CACHE_STRATEGY_NAME,
  createTopicCacheStrategy,
  serializeEnvelope,
  topicCacheKeyOf,
  type TopicCacheKey,
  type TopicCacheReader,
  type TopicCacheStrategyOptions,
} from './strategies/topic-cache'
export {
  DEFAULT_WEB_FALLBACK_MODE,
  WEB_FALLBACK_STRATEGY_NAME,
  createWebFallbackStrategy,
  type WebFallbackMode,
  type WebFallbackOptions,
} from './strategies/web-fallback'
export { parseReadPageHtml } from './web/read-html'
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
