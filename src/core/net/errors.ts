import { AUTH_LEVEL_SERVER_MESSAGES, FAKE_ERROR_MESSAGES } from './constants'
import type { FetchDiagnostic } from './diagnostics'
import { isRecord } from './is-record'
import { stripServerHtml } from './server-text'

export type NgaErrorKind =
  /** 传输层失败：DNS、超时、连接断 */
  | 'network'
  /** HTTP 状态错误，且 body 里没有可用的错误信息 */
  | 'http'
  /** 响应洗不成合法 JSON / 结构不对——基本等价于被封（ADR-0002） */
  | 'parse'
  /** 服务端明确返回了 error 对象 */
  | 'server'
  /** 策略不适用（例如缓存里没有这条），仅用于策略链内部流转 */
  | 'unavailable'

export interface NgaErrorOptions {
  readonly kind: NgaErrorKind
  readonly message: string
  /** 服务端错误码；JSON 侧取 `error.code`，缺失时为 '?' */
  readonly code?: string | number
  readonly status?: number
  /** 触发本次失败的策略名 */
  readonly via?: string
  readonly cause?: unknown
  /** 未提供时按 kind 推导 */
  readonly retryable?: boolean
}

/**
 * 除了服务端语义错误，其余失败都值得换下一档策略试试。
 * 服务端明确说「找不到主题」这类语义错误换几次策略也还是这个结果，直接抛给调用方。
 *
 * 比 MNGA 的判据（只有解析失败/HTTP 状态错误才重试）多放了 `network`：
 * 本项目的链末端是帖子缓存与网页兜底，断网时正该落到缓存那一档，
 * 而不是在第一档就把错误抛给用户。调用方主动取消的请求会显式传 `retryable: false`。
 */
function defaultRetryable(kind: NgaErrorKind): boolean {
  return kind !== 'server'
}

export class NgaError extends Error {
  readonly kind: NgaErrorKind
  readonly code?: string | number
  readonly status?: number
  readonly via?: string
  readonly retryable: boolean
  /**
   * 反封锁链跑完之后由 `runStrategyChain` 补上（构造时还不知道后面会试几档）。
   * 错误页拿它渲染诊断摘要。故意可写：重新包一个 NgaError 会丢掉调用方
   * 用 `instanceof` / 引用比较建立的那些判断。
   */
  diagnostic?: FetchDiagnostic

  constructor(options: NgaErrorOptions) {
    super(options.message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'NgaError'
    this.kind = options.kind
    this.code = options.code
    this.status = options.status
    this.via = options.via
    this.retryable = options.retryable ?? defaultRetryable(options.kind)
  }
}

/** 服务端 error 对象抽出来的结构。 */
export interface NgaServerError {
  readonly code: string | number
  readonly message: string
}

/**
 * 命中「假错误」白名单的（API 文档 §0.7）视为成功。
 * 白名单里的词是子串匹配：真实响应里会出现「发贴完毕」「操作完毕」这类前缀。
 */
export function isFakeError(message: string): boolean {
  return FAKE_ERROR_MESSAGES.some((fake) => message.includes(fake))
}

/**
 * 这条服务端错误是不是「这一发没带上身份」而不是语义错误（API 文档 §0.7 之外的经验规则）。
 * 命中的话值得换个组合再试一次，判据与出处见 `AUTH_LEVEL_SERVER_MESSAGES`。
 */
export function isAuthLevelServerError(message: string): boolean {
  return AUTH_LEVEL_SERVER_MESSAGES.some((hint) => message.includes(hint))
}

/**
 * 从顶层响应对象里抽 JSON 错误（API 文档 §0.7）：
 * `{"error":{"0":"信息"}}` 或 `{"error":{"code":403,"0":"信息"}}`。
 * 没有错误时返回 null。
 *
 * 说明文字在这儿就剥成纯文本（`stripServerHtml`）：NGA 的错误说明是给网页版
 * innerHTML 用的，带 `<br/>` 与 `<a>`；抽取口只有这一个，剥在这里错误页、诊断日志、
 * 各处 Toast 就都拿到人话，不必各自记得剥一遍。
 */
export function extractServerError(root: unknown): NgaServerError | null {
  if (!isRecord(root)) return null
  const error = root.error
  if (error === undefined || error === null) return null

  if (typeof error === 'string') {
    // 剥完只剩空的（整条说明全是标签）仍然算错误——空说明比误判成功强
    return error === '' ? null : { code: '?', message: stripServerHtml(error) || unknownMessage('?') }
  }
  // 数组形态（`{"error":["访问速度过快"]}`）：`isRecord` 把数组排除在外，
  // 不单独认一下的话服务端说的原话会被整条丢掉，用户看到的是「响应里没有 data」
  // 这种毫无信息量的话（2026-08-13，「版块全空」排查）
  if (Array.isArray(error)) {
    const messages = error
      .filter((item): item is string => typeof item === 'string')
      .map(stripServerHtml)
      .filter((message) => message !== '')
    // 空数组不当错误：PHP 的空数组序列化出来就是 `[]`，和「这个字段没内容」分不开，
    // 而对象形态那一档（有 error 对象但没有可读信息）仍然算错误——那是明确的结构
    return messages.length === 0 ? null : { code: '?', message: messages.join('；') }
  }
  if (!isRecord(error)) return null

  const rawCode = error.code
  const code = typeof rawCode === 'string' || typeof rawCode === 'number' ? rawCode : '?'

  const messages: string[] = []
  for (const [key, value] of Object.entries(error)) {
    if (key === 'code') continue
    if (typeof value !== 'string') continue
    const message = stripServerHtml(value)
    if (message !== '') messages.push(message)
  }
  if (messages.length === 0) {
    // 有 error 对象但没有可读信息，仍然算错误
    return { code, message: unknownMessage(code) }
  }
  return { code, message: messages.join('；') }
}

function unknownMessage(code: string | number): string {
  return `未知错误（code=${String(code)}）`
}
