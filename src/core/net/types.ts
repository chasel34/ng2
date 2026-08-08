import type { AuthMode, NgaCredentials } from './auth'
import type { ComboCache } from './combo'
import type { ResponseFormat, UserAgentProfile } from './constants'
import type { FetchDiagnostic } from './diagnostics'
import type { NgaEnvelope } from './envelope'
import type { NgaError } from './errors'
import type { QueryParams } from './query'
import type { HttpTransport } from './transport'

/** 一次逻辑上的 NGA 读/写请求，与具体走哪条策略无关。 */
export interface NgaRequest {
  /** 端点路径，如 `thread.php`、`nuke.php` */
  readonly path: string
  /** 业务参数，一律放 URL query（NGA 的惯例，API 文档 §0.4） */
  readonly query?: QueryParams
  /** POST 表单字段，通常只有认证信息 */
  readonly form?: QueryParams
  /** 默认 POST（MNGA 的做法，Android 混用 GET/POST，效果相同） */
  readonly method?: 'GET' | 'POST'
  /** 期望的返回格式，默认 `json`；反封锁链会在这个维度上交替 */
  readonly format?: ResponseFormat
  /** 覆盖 UA 档位，例如 read.php 用 windowsPhone */
  readonly userAgent?: UserAgentProfile
  /** 覆盖认证方式 */
  readonly auth?: AuthMode
  /** 覆盖账号（反封锁链「换账号重试」那一档要用） */
  readonly credentials?: NgaCredentials | null
  /** 覆盖域名 */
  readonly host?: string
  /** 覆盖 Referer；`nuke.php?__lib=ucp` 必须带且需以 base url 开头 */
  readonly referer?: string
  /**
   * 同 `referer`，但只写路径，由策略补上当前 host——调用方不必知道自己会被发到哪个域名
   * （反封锁链会换域名）。`referer` 已给出时以它为准。
   */
  readonly refererPath?: string
  readonly signal?: AbortSignal
}

/** 一次成功的请求结果。`via` 是产出它的策略名，便于排障与埋点。 */
export interface NgaResult extends NgaEnvelope {
  readonly via: string
}

/** 策略运行时能拿到的东西。 */
export interface FetchContext {
  readonly transport: HttpTransport
  readonly host: string
  readonly authMode: AuthMode
  readonly credentials: NgaCredentials | null
  /** 各 UA 档位的实际取值（webview 档由设备侧注入系统 UA） */
  readonly userAgents: Readonly<Record<UserAgentProfile, string>>
  /**
   * 重建一个新的 HTTP client（ADR-0002：每次重试前重建）。
   * 不给就一直用 `transport`——单测与不做反封锁的场合不需要。
   */
  readonly renewTransport?: () => HttpTransport
  /** 成功组合缓存（格式 × 域名），链上各档共用一份 */
  readonly comboCache?: ComboCache
  /** `read.php` 的 UA 档位开关（ADR-0002，默认不开） */
  readonly readPhpUserAgent?: UserAgentProfile
  readonly onEvent?: (event: FetchEvent) => void
}

export type StrategyOutcome =
  | { readonly ok: true; readonly result: NgaResult }
  | { readonly ok: false; readonly error: NgaError }

/**
 * 反封锁链（ADR-0002）的一环。
 *
 * 链上依次是：格式参数交替 → 换账号重试 → Web 反解 → 帖子缓存 → 网页兜底。
 * 本票只落地第一档的单策略 direct，其余留槽位：只要实现这个接口塞进 strategies 数组即可，
 * 上层调用方感知不到。
 */
export interface FetchStrategy {
  readonly name: string
  run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome>
}

export type FetchEvent =
  | { readonly type: 'strategy-start'; readonly strategy: string; readonly path: string }
  | { readonly type: 'strategy-success'; readonly strategy: string; readonly path: string }
  | {
      readonly type: 'strategy-failure'
      readonly strategy: string
      readonly path: string
      readonly error: NgaError
    }
  /** 一次真正发出去的 HTTP 尝试。诊断日志（22 号票）就是由这些攒出来的 */
  | {
      readonly type: 'attempt'
      readonly strategy: string
      readonly path: string
      readonly format: ResponseFormat
      readonly host: string
      readonly userAgent: UserAgentProfile
      readonly userAgentValue: string
      /** 这次用的账号 uid；游客为 null */
      readonly uid: string | null
      /** 成功时不带 */
      readonly error?: NgaError
    }
  /** 整条链失败。`diagnostic` 已经带上全部尝试记录，可直接落盘 */
  | { readonly type: 'chain-failure'; readonly diagnostic: FetchDiagnostic }
