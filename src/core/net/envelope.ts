import { NgaError, extractServerError, isFakeError, type NgaServerError } from './errors'
import { sanitizeNgaJson } from './sanitize'

/**
 * 洗干净并解析后的一次响应。
 *
 * 顶层结构是 `{"data": {...}, "error": {...}, "time": N}`，`data` 与 `error` 互斥
 * （API 文档 §0.6）。`data` 内部大量用字符串数字键当数组，自动映射全部失效，
 * 只能由 core/api 手工遍历——所以这里到 `unknown` 为止，不再往下猜结构。
 */
export interface NgaEnvelope {
  /** 顶层对象原样 */
  readonly root: unknown
  /** 顶层 `data`；少数不套壳的接口（app_api 版块分类树）里 data === root */
  readonly data: unknown
  readonly time?: number
  /** 命中假错误白名单时保留，调用方需自行判 data 是否为空 */
  readonly fakeError?: NgaServerError
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 清洗 → JSON.parse → 错误判定。
 *
 * 解析失败抛 `kind: 'parse'` 的 NgaError（可重试：解析失败基本等价于被封）；
 * 服务端真错误抛 `kind: 'server'`（不重试）；假错误白名单里的错误当成功返回。
 */
export function parseNgaJson(text: string, via?: string): NgaEnvelope {
  const cleaned = sanitizeNgaJson(text)
  if (cleaned === '') {
    throw new NgaError({ kind: 'parse', message: '响应为空', via })
  }

  let root: unknown
  try {
    root = JSON.parse(cleaned) as unknown
  } catch (cause) {
    throw new NgaError({
      kind: 'parse',
      message: `响应不是合法 JSON：${cleaned.slice(0, 120)}`,
      via,
      cause,
    })
  }

  if (!isRecord(root)) {
    throw new NgaError({ kind: 'parse', message: '响应顶层不是对象', via })
  }

  const serverError = extractServerError(root)
  if (serverError && !isFakeError(serverError.message)) {
    throw new NgaError({
      kind: 'server',
      message: serverError.message,
      code: serverError.code,
      via,
    })
  }

  const time = typeof root.time === 'number' ? root.time : undefined
  return {
    root,
    // 只在既没有 data 也没有 error 的时候才把顶层当 data（app_api 版块分类树是这种）；
    // 只有 error 的响应 data 必须是 undefined，否则假错误会被当成有数据
    data: 'data' in root ? root.data : 'error' in root ? undefined : root,
    time,
    fakeError: serverError ?? undefined,
  }
}
