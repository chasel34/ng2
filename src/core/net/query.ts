import { gbkEncodeURIComponent } from './encoding/gb18030'

/**
 * 需要用 GBK 而非 UTF-8 编码的参数值（API 文档 §0.5：参数编码不统一，必须逐接口对照，
 * 例如 thread.php 的 `key` 是 UTF-8 而同一接口的 `author` 是 GBK）。
 */
export interface GbkParam {
  readonly charset: 'gbk'
  readonly value: string
}

/** 标记某个参数值按 GBK 编码。 */
export function gbk(value: string): GbkParam {
  return { charset: 'gbk', value }
}

export type QueryValue = string | number | boolean | GbkParam | null | undefined

export type QueryParams = Readonly<Record<string, QueryValue>>

function isGbkParam(value: QueryValue): value is GbkParam {
  return typeof value === 'object' && value !== null && 'charset' in value
}

/**
 * 归一化成字符串；返回 null 表示这个参数应当被剔除。
 *
 * **空值参数必须从 query 中删除**（API 文档 §0.4）：大量调用逻辑依赖它——
 * bool false 编码成空串即「不传」、fid/stid 二选一等。
 */
function normalize(value: QueryValue): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'boolean') return value ? '1' : null
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : null
  if (isGbkParam(value)) return value.value === '' ? null : gbkEncodeURIComponent(value.value)
  return value === '' ? null : encodeURIComponent(value)
}

/** 拼 query string（已剔除空值参数，值已按各自字符集编码）；无参数时返回空串。 */
export function buildQueryString(params: QueryParams): string {
  const pairs: string[] = []
  for (const [key, value] of Object.entries(params)) {
    const encoded = normalize(value)
    if (encoded === null) continue
    pairs.push(`${encodeURIComponent(key)}=${encoded}`)
  }
  return pairs.join('&')
}

/**
 * 拼 POST 表单体。与 query 同规则（空值剔除），但整体按 UTF-8/GBK 逐值编码后
 * 用 `application/x-www-form-urlencoded` 提交。
 */
export function buildFormBody(params: QueryParams): string {
  return buildQueryString(params)
}
