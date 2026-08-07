import type { AuthMode, NgaCredentials } from './auth'
import { DEFAULT_NGA_HOST, USER_AGENT_PROFILES, type UserAgentProfile } from './constants'
import { NgaError } from './errors'
import { createDirectStrategy } from './strategies/direct'
import { createFetchTransport, type HttpTransport } from './transport'
import type { FetchContext, FetchStrategy, NgaRequest, NgaResult } from './types'

export interface NgaFetcherOptions {
  /** 默认用全局 fetch；设备侧注入 expo/fetch，单测注入假实现 */
  readonly transport?: HttpTransport
  /** 默认域名，可被单条请求覆盖 */
  readonly host?: string
  /** 默认认证方式（API 文档 §0.2 的两种等价方式） */
  readonly authMode?: AuthMode
  /** 每次请求时取当前账号；返回 null 即游客 */
  readonly getCredentials?: () => NgaCredentials | null
  /** 设备侧的系统 WebView UA（Android v4 的做法），不给就用兜底常量 */
  readonly webViewUserAgent?: string
  /**
   * 反封锁链（ADR-0002）。默认只有 direct 一档；
   * 后续把「格式参数交替 / 换账号 / Web 反解 / 帖子缓存 / 网页兜底」按序追加即可。
   */
  readonly strategies?: readonly FetchStrategy[]
  readonly onEvent?: FetchContext['onEvent']
}

export type NgaFetcher = (request: NgaRequest) => Promise<NgaResult>

/**
 * 按顺序跑策略链：
 * - 谁先成功用谁；
 * - 失败且 `retryable`（解析失败/HTTP 状态错误/网络错误 ≈ 被封）→ 换下一档；
 * - 失败且不可重试（服务端语义错误）→ 立刻抛出，不浪费后面的兜底。
 */
export async function runStrategyChain(
  strategies: readonly FetchStrategy[],
  request: NgaRequest,
  context: FetchContext,
): Promise<NgaResult> {
  if (strategies.length === 0) {
    throw new NgaError({ kind: 'unavailable', message: '没有可用的请求策略' })
  }

  let lastError: NgaError | undefined
  for (const strategy of strategies) {
    context.onEvent?.({ type: 'strategy-start', strategy: strategy.name, path: request.path })
    const outcome = await strategy.run(request, context)
    if (outcome.ok) {
      context.onEvent?.({ type: 'strategy-success', strategy: strategy.name, path: request.path })
      return outcome.result
    }
    context.onEvent?.({
      type: 'strategy-failure',
      strategy: strategy.name,
      path: request.path,
      error: outcome.error,
    })
    lastError = outcome.error
    if (!outcome.error.retryable) throw outcome.error
  }
  throw lastError ?? new NgaError({ kind: 'unavailable', message: '所有策略都没有产出结果' })
}

/** 建一个带策略链的 NGA 请求器。 */
export function createNgaFetcher(options: NgaFetcherOptions = {}): NgaFetcher {
  const transport = options.transport ?? createFetchTransport()
  const strategies = options.strategies ?? [createDirectStrategy()]
  const userAgents: Record<UserAgentProfile, string> = {
    ...USER_AGENT_PROFILES,
    webview: options.webViewUserAgent ?? USER_AGENT_PROFILES.webview,
  }

  return (request) =>
    runStrategyChain(strategies, request, {
      transport,
      host: options.host ?? DEFAULT_NGA_HOST,
      authMode: options.authMode ?? 'cookie',
      credentials: options.getCredentials?.() ?? null,
      userAgents,
      onEvent: options.onEvent,
    })
}
