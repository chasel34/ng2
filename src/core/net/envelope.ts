import { NgaError, extractServerError, isFakeError, type NgaServerError } from './errors'
import { isRecord } from './is-record'
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

/**
 * 信封形状。
 *
 * - `wrapped`（默认）：顶层是 `{"data":…}` 或 `{"error":…}`，绝大多数接口都是这样；
 * - `bare`：顶层本身就是数据，没有 `data` 壳。
 *
 * **默认必须是 `wrapped`**（2026-08-13，「版块全空」排查）：以前是「既没有 data 也没有
 * error 就把顶层当 data」，于是**任何**一个陌生的 JSON 对象（别的接口的响应、镜像域名的
 * 落地页、没验证过的格式档吐出来的东西）都会变成一份「合法但没有任何业务字段」的数据，
 * 一路走到 UI 变成「这个版块还没有主题」，还会被反封锁链当成「这个组合是好的」记进缓存。
 * 需要 bare 的接口自己声明（`NgaRequest.envelope`）。
 */
export type EnvelopeShape = 'wrapped' | 'bare'

/**
 * 清洗 → JSON.parse → 错误判定。
 *
 * 解析失败抛 `kind: 'parse'` 的 NgaError（可重试：解析失败基本等价于被封）；
 * 服务端真错误抛 `kind: 'server'`（不重试）；假错误白名单里的错误当成功返回。
 */
export function parseNgaJson(
  text: string,
  via?: string,
  shape: EnvelopeShape = 'wrapped',
): NgaEnvelope {
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
  const shelled = 'data' in root || 'error' in root
  if (!shelled && shape === 'wrapped') {
    throw new NgaError({
      kind: 'parse',
      message: `响应顶层既没有 data 也没有 error：${cleaned.slice(0, 120)}`,
      via,
    })
  }

  return {
    root,
    // 只有 error 的响应 data 必须是 undefined，否则假错误会被当成有数据；
    // 顶层当 data 只在调用方显式声明 bare 时才发生
    data: shelled ? ('data' in root ? root.data : undefined) : root,
    time,
    fakeError: serverError ?? undefined,
  }
}
