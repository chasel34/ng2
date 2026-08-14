import type { AuthMode, NgaCredentials } from './auth'
import { createComboCache, type ComboCache } from './combo'
import { DEFAULT_NGA_HOST, USER_AGENT_PROFILES, type UserAgentProfile } from './constants'
import {
  formatOutcome,
  summarizeEnvelopeData,
  type FetchAttemptLog,
  type FetchDiagnostic,
  type FetchOutcomeSummary,
} from './diagnostics'
import { NgaError } from './errors'
import { isGbkParam, type QueryParams } from './query'
import { createDirectStrategy } from './strategies/direct'
import { createFetchTransport, type HttpTransport } from './transport'
import type { FetchContext, FetchEvent, FetchStrategy, NgaRequest, NgaResult } from './types'

export interface NgaFetcherOptions {
  /** 默认用全局 fetch；设备侧注入 expo/fetch，单测注入假实现 */
  readonly transport?: HttpTransport
  /**
   * 现造一个 HTTP client。给了它就代替 `transport`，并让链在每次重试前重建
   * （ADR-0002 / MNGA 的做法）。
   */
  readonly createTransport?: () => HttpTransport
  /** 默认域名，可被单条请求覆盖 */
  readonly host?: string
  /**
   * 每次请求时取默认域名（22 号票的「NGA 域名」设置项）。
   * 给了它就盖过 `host`——设置页改完，下一个请求就发到新域名，不用重建 fetcher。
   */
  readonly getHost?: () => string
  /**
   * 默认认证方式（API 文档 §0.2 的两种等价方式），默认 `both`。
   * 为什么不是 `cookie`：见 auth.ts 文件头的 okhttp cookie jar 取证。
   */
  readonly authMode?: AuthMode
  /** 每次请求时取当前账号；返回 null 即游客 */
  readonly getCredentials?: () => NgaCredentials | null
  /** 设备侧的系统 WebView UA（Android v4 的做法），不给就用兜底常量 */
  readonly webViewUserAgent?: string
  /**
   * `read.php` 的 UA 档位开关（ADR-0002：可切 Windows Phone UA，默认关）。
   * 每次请求现取，设置页改了立刻生效。
   */
  readonly getReadPhpUserAgent?: () => UserAgentProfile | null
  /**
   * 反封锁链（ADR-0002）。默认只有 direct 一档；
   * 设备侧按序排「格式参数交替 → 换账号 → Web 反解 → 帖子缓存 → 网页兜底」。
   */
  readonly strategies?: readonly FetchStrategy[]
  /** 成功组合缓存，默认现建一个内存版；多个 fetcher 想共用时传同一个 */
  readonly comboCache?: ComboCache
  /** 整条链失败时的诊断记录去处（设备侧写本地日志，22 号票导出） */
  readonly onDiagnostic?: (diagnostic: FetchDiagnostic) => void
  readonly onEvent?: FetchContext['onEvent']
}

export type NgaFetcher = (request: NgaRequest) => Promise<NgaResult>

/** 诊断摘要里只留业务参数：`__inchst`/`__output` 这些是我们自己拼的，排障没用。 */
function diagnosticParams(query: QueryParams | undefined): Record<string, string> {
  const params: Record<string, string> = {}
  if (query === undefined) return params
  for (const [key, value] of Object.entries(query)) {
    if (key.startsWith('__')) continue
    if (value === null || value === undefined || value === '') continue
    params[key] = isGbkParam(value) ? value.value : String(value)
  }
  return params
}

function toAttemptLog(event: Extract<FetchEvent, { type: 'attempt' }>): FetchAttemptLog {
  return {
    strategy: event.strategy,
    format: event.format,
    host: event.host,
    userAgent: event.userAgent,
    userAgentValue: event.userAgentValue,
    uid: event.uid,
    ...(event.error === undefined
      ? {}
      : {
          error: {
            kind: event.error.kind,
            message: event.error.message,
            ...(event.error.status === undefined ? {} : { status: event.error.status }),
          },
        }),
  }
}

/**
 * 按顺序跑策略链：
 * - 谁先成功用谁；
 * - 失败且 `retryable`（解析失败/HTTP 状态错误/网络错误 ≈ 被封）→ 换下一档；
 * - 失败且不可重试（服务端语义错误）→ 立刻抛出，不浪费后面的兜底。
 *
 * 链上每一次真正发出去的请求都被记下来，失败时攒成一条诊断挂到错误上并发
 * `chain-failure` 事件——错误页要显示的「试过哪些组合」就是它。
 */
export async function runStrategyChain(
  strategies: readonly FetchStrategy[],
  request: NgaRequest,
  context: FetchContext,
): Promise<NgaResult> {
  const attempts: FetchAttemptLog[] = []
  const outer = context.onEvent
  const onEvent = (event: FetchEvent) => {
    if (event.type === 'attempt') attempts.push(toAttemptLog(event))
    outer?.(event)
  }
  const scoped: FetchContext = { ...context, onEvent }

  const fail = (error: NgaError): NgaError => {
    const diagnostic: FetchDiagnostic = {
      at: Date.now(),
      path: request.path,
      params: diagnosticParams(request.query),
      message: error.message,
      attempts,
    }
    error.diagnostic = diagnostic
    onEvent({ type: 'chain-failure', diagnostic })
    return error
  }

  if (strategies.length === 0) {
    throw fail(new NgaError({ kind: 'unavailable', message: '没有可用的请求策略' }))
  }

  /**
   * 成功也留一条记录（H2）。以前只有整条链失败才有诊断，于是「链自认为成功、
   * 拿回来的却是空数据」这种静默降级完全看不见——真出过这个事故。
   */
  const succeed = (result: NgaResult): NgaResult => {
    const last = attempts.at(-1)
    const { keys, rows } = summarizeEnvelopeData(result.data)
    const success: FetchOutcomeSummary = {
      strategy: result.via,
      format: last?.format ?? '(未发请求)',
      host: last?.host ?? '(未发请求)',
      keys,
      ...(rows === undefined ? {} : { rows }),
    }
    onEvent({
      type: 'chain-success',
      diagnostic: {
        at: Date.now(),
        path: request.path,
        params: diagnosticParams(request.query),
        message: formatOutcome(success),
        attempts,
        success,
      },
    })
    return result
  }

  let lastError: NgaError | undefined
  for (const strategy of strategies) {
    onEvent({ type: 'strategy-start', strategy: strategy.name, path: request.path })
    const outcome = await strategy.run(request, scoped)
    if (outcome.ok) {
      onEvent({ type: 'strategy-success', strategy: strategy.name, path: request.path })
      return succeed(outcome.result)
    }
    onEvent({
      type: 'strategy-failure',
      strategy: strategy.name,
      path: request.path,
      error: outcome.error,
    })
    // `unavailable` 是「这一档不适用」（只有一个账号、缓存里没有这条），不是失败的原因。
    // 已经有更实质的错误时别让它盖过去——否则用户看到的是「没有可换的账号」
    // 而不是真正该说的「这一页被封了」。
    if (
      outcome.error.kind !== 'unavailable' ||
      lastError === undefined ||
      lastError.kind === 'unavailable'
    ) {
      lastError = outcome.error
    }
    if (!outcome.error.retryable) throw fail(outcome.error)
  }
  throw fail(lastError ?? new NgaError({ kind: 'unavailable', message: '所有策略都没有产出结果' }))
}

/** 建一个带策略链的 NGA 请求器。 */
export function createNgaFetcher(options: NgaFetcherOptions = {}): NgaFetcher {
  const renewTransport = options.createTransport
  const transport = renewTransport?.() ?? options.transport ?? createFetchTransport()
  const strategies = options.strategies ?? [createDirectStrategy()]
  const comboCache = options.comboCache ?? createComboCache()
  const userAgents: Record<UserAgentProfile, string> = {
    ...USER_AGENT_PROFILES,
    webview: options.webViewUserAgent ?? USER_AGENT_PROFILES.webview,
  }

  return (request) => {
    const readPhpUserAgent = options.getReadPhpUserAgent?.() ?? undefined
    const onDiagnostic = options.onDiagnostic
    const outer = options.onEvent
    return runStrategyChain(strategies, request, {
      transport,
      host: options.getHost?.() ?? options.host ?? DEFAULT_NGA_HOST,
      authMode: options.authMode ?? 'both',
      credentials: options.getCredentials?.() ?? null,
      userAgents,
      comboCache,
      ...(renewTransport === undefined ? {} : { renewTransport }),
      ...(readPhpUserAgent === undefined ? {} : { readPhpUserAgent }),
      ...(outer === undefined && onDiagnostic === undefined
        ? {}
        : {
            onEvent: (event: FetchEvent) => {
              if (event.type === 'chain-failure' || event.type === 'chain-success') {
                onDiagnostic?.(event.diagnostic)
              }
              outer?.(event)
            },
          }),
    })
  }
}
