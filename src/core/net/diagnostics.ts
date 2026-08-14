/**
 * 反封锁链的诊断记录（ADR-0002）。
 *
 * 链跑到底还是失败时，把「这次请求试过哪些组合、每一档为什么败」摊成一条可读记录，
 * 由设备侧写进本地日志（22 号票的「导出诊断日志」消费它），错误页也从这里取
 * 「tid=… · page=… · ua=…」那一行。
 *
 * 纯数据 + 纯格式化，不 import NgaError：这样 errors.ts 能反过来引用 FetchDiagnostic
 * 而不成环；NgaError → FetchAttemptError 的转换在 fetcher.ts 里做。
 */

/** 一次尝试失败的原因，摊平成纯数据（对应 NgaError 的 kind/message/status）。 */
export interface FetchAttemptError {
  readonly kind: string
  readonly message: string
  readonly status?: number
}

/** 链上一次实际发出的 HTTP 尝试。成功的那次 `error` 为 undefined。 */
export interface FetchAttemptLog {
  readonly strategy: string
  /** 格式参数档位，如 `json`（`__output=8`） */
  readonly format: string
  readonly host: string
  /** UA 档位名（`webview` / `windowsPhone` …），完整 UA 串在 userAgentValue */
  readonly userAgent: string
  readonly userAgentValue: string
  /** 这次尝试用的账号 uid；游客为 null */
  readonly uid: string | null
  readonly error?: FetchAttemptError
}

/**
 * 成功那一次的落点摘要（2026-08-13，「版块全空」排查）。
 *
 * 以前只有整条链失败才留记录，于是**「链自认为成功、但拿回来的是一份空数据」
 * 这种静默降级完全不可观测**——线上那次「所有版块都空」正是这种。这里记的是
 * 纯结构信息（哪个组合、`data` 顶层有哪些键、列表有几条），不含任何正文与凭证。
 */
export interface FetchOutcomeSummary {
  /** 产出结果的策略名 */
  readonly strategy: string
  /** 格式档位名，如 `json`（`__output=8`） */
  readonly format: string
  readonly host: string
  /** `data` 顶层的键，超出上限就截断 */
  readonly keys: readonly string[]
  /** 列表类接口的条数（`__T` / `__R` 的元素个数），不是列表就没有 */
  readonly rows?: number
}

export interface FetchDiagnostic {
  /** 记录时刻（ms since epoch） */
  readonly at: number
  readonly path: string
  /** 业务参数（tid/page/…），已归一化成字符串，`__` 开头的框架参数不计 */
  readonly params: Readonly<Record<string, string>>
  /** 最终抛给调用方的错误说明；成功记录里是落点的一句话 */
  readonly message: string
  readonly attempts: readonly FetchAttemptLog[]
  /** 有它就是一条**成功**记录；没有就是整条链失败 */
  readonly success?: FetchOutcomeSummary
}

/** 摘要里最多列几个 `data` 顶层键。 */
const SUMMARY_KEY_LIMIT = 8

/**
 * 从一次成功的响应里抠出可记录的结构信息。
 * 只看键名与条数——正文一律不进日志（这份日志是要导出发给别人看的）。
 */
export function summarizeEnvelopeData(data: unknown): { keys: readonly string[]; rows?: number } {
  if (typeof data !== 'object' || data === null) return { keys: [] }
  const record = data as Record<string, unknown>
  const keys = Object.keys(record).slice(0, SUMMARY_KEY_LIMIT)
  for (const listKey of ['__T', '__R']) {
    const list = record[listKey]
    if (typeof list === 'object' && list !== null) {
      return { keys, rows: Object.keys(list as Record<string, unknown>).length }
    }
  }
  return { keys }
}

/** 摘要里最多列几个业务参数——设计稿那一行只放得下 tid/page 这种量级。 */
const SUMMARY_PARAM_LIMIT = 3

/**
 * 错误页上那一行诊断摘要（设计稿 isError：`tid=42800000 · page=1 · ua=app/1.0.0`）。
 * UA 取最后一次尝试的**档位名**而不是完整 UA 串——一行放不下，且要排查的正是档位。
 */
export function diagnosticSummary(diagnostic: FetchDiagnostic): string {
  const parts = Object.entries(diagnostic.params)
    .slice(0, SUMMARY_PARAM_LIMIT)
    .map(([key, value]) => `${key}=${value}`)
  const last = diagnostic.attempts.at(-1)
  if (last !== undefined) parts.push(`ua=${last.userAgent}`)
  return parts.join(' · ')
}

function formatAttempt(attempt: FetchAttemptLog, index: number): string {
  const combo = `${attempt.format} @ ${attempt.host}`
  const who = attempt.uid === null ? '游客' : `uid=${attempt.uid}`
  const result =
    attempt.error === undefined
      ? 'ok'
      : `${attempt.error.kind}${attempt.error.status === undefined ? '' : ` ${attempt.error.status}`}: ${attempt.error.message}`
  return `  ${index + 1}. [${attempt.strategy}] ${combo} ua=${attempt.userAgent} ${who} → ${result}`
}

/** 成功记录的落点那一行：用了哪个组合、拿回来什么形状。 */
export function formatOutcome(success: FetchOutcomeSummary): string {
  const rows = success.rows === undefined ? '' : ` ${success.rows} 条`
  const keys = success.keys.length === 0 ? '（无字段）' : success.keys.join(',')
  return `[${success.strategy}] ${success.format} @ ${success.host} → data{${keys}}${rows}`
}

/**
 * 落本地日志的文本形态。一条记录多行：首行是请求与最终结果，其后每行一次尝试。
 * 存文本而不是 JSON：这份日志的唯一消费者是人（22 号票导出后发给自己看）。
 */
export function formatDiagnostic(diagnostic: FetchDiagnostic): string {
  const query = Object.entries(diagnostic.params)
    .map(([key, value]) => `${key}=${value}`)
    .join('&')
  const target = query === '' ? diagnostic.path : `${diagnostic.path}?${query}`
  const verdict =
    diagnostic.success === undefined
      ? `失败：${diagnostic.message}`
      : `成功：${formatOutcome(diagnostic.success)}`
  const head = `${new Date(diagnostic.at).toISOString()} ${target} ${verdict}`
  return [head, ...diagnostic.attempts.map(formatAttempt)].join('\n')
}

/** 「加载失败」页上那两行说明。 */
export interface FetchFailureCopy {
  /** 服务端到底返回了什么。普通字重的那半句（`服务端返回` / `连不上服务器`） */
  readonly headline: string
  /**
   * 状态码那一截（`HTTP 403`）。设计稿只把它加粗并换等宽字，前面那半句是正常正文——
   * 所以拆成两个字段，别再让页面去猜从哪儿断开。没有状态码的档不给这个字段。
   */
  readonly code?: string
  /** 为什么会这样、还能怎么办 */
  readonly hint: string
}

/**
 * 把链上的失败翻成人话（设计稿 isError：`服务端返回 HTTP 403 Forbidden` +
 * `通常是客户端 UA 被拒或需要重新登录`）。
 *
 * 参数按结构写而不是收 NgaError：这个模块要能被 errors.ts 反向引用，不能成环。
 */
export function describeFetchFailure(error: {
  readonly kind: string
  readonly status?: number
  readonly message: string
}): FetchFailureCopy {
  const code = error.status === undefined ? undefined : { code: `HTTP ${error.status}` }
  switch (error.kind) {
    case 'server':
      // 服务端把话说清楚了（权限不足、找不到主题…），照搬比我们编强
      return { headline: error.message, hint: '这是论坛给出的说明，换个页面或重新登录再试' }
    case 'http':
      return {
        headline: code === undefined ? '服务端没有返回内容' : '服务端返回',
        ...code,
        hint: '通常是客户端 UA 被拒或需要重新登录',
      }
    case 'parse':
      return {
        headline: code === undefined ? '响应内容解析不了' : '服务端返回',
        ...code,
        hint: '第三方客户端被拦是最常见的原因，可以先用网页版打开',
      }
    case 'network':
      return { headline: '连不上服务器', hint: '检查网络连接后重试' }
    default:
      return { headline: '这一页没有可用的加载方式', hint: '所有兜底都试过了，可以用网页版打开' }
  }
}

/** 本地日志保留多少条。被封时一屏能连打十几条，留太多既没用又占地方。 */
export const DIAGNOSTIC_LOG_LIMIT = 50

/**
 * 往日志里追加一条并裁到上限（最新的在最后）。
 * 纯函数，落盘在 src/store/diagnostics.ts。
 */
export function appendDiagnosticLog(
  log: readonly string[],
  diagnostic: FetchDiagnostic,
  limit: number = DIAGNOSTIC_LOG_LIMIT,
): readonly string[] {
  const next = [...log, formatDiagnostic(diagnostic)]
  return next.length <= limit ? next : next.slice(next.length - limit)
}
