import { isRecord } from '../net'

/**
 * 手工遍历 NGA 响应的公共小工具。
 *
 * NGA 的 `data` 里**大量用字符串数字键当数组**（API 文档 §0.6），字段随时可能缺、
 * 可能换类型，自动 JSON 映射全线失效。各接口的解析器都要靠这几个函数把
 * 「取一个字符串」「取一个整数」「按数组顺序遍历」写得短一点。
 */

/** 按数字键升序取键值对；非数字键排在后面，保持原有顺序。 */
export function orderedEntries(value: unknown): (readonly [string, unknown])[] {
  if (!isRecord(value)) return []
  return Object.entries(value)
    .map((entry, index) => ({ entry, order: Number(entry[0]), index }))
    .sort((a, b) => {
      const aNum = Number.isFinite(a.order)
      const bNum = Number.isFinite(b.order)
      if (aNum && bNum) return a.order - b.order
      if (aNum !== bNum) return aNum ? -1 : 1
      return a.index - b.index
    })
    .map(({ entry }) => [entry[0], entry[1]] as const)
}

/** 同 `orderedEntries`，只要值。 */
export const orderedValues = (value: unknown): unknown[] =>
  orderedEntries(value).map(([, item]) => item)

/** 非空字符串字段（已 trim），否则 undefined。 */
export function str(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key]
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

/** 整数字段。服务端偶尔把数字写成字符串，一并收下。 */
export function int(record: Record<string, unknown>, key: string): number | undefined {
  const value = record[key]
  if (typeof value === 'number') return Number.isFinite(value) ? Math.trunc(value) : undefined
  if (typeof value !== 'string') return undefined
  const parsed = Number(value.trim())
  return Number.isFinite(parsed) ? Math.trunc(parsed) : undefined
}

/**
 * 把 0 当成「没这个值」。
 * NGA 分不清「字段缺省」与「填 0」——普通版块就常带一个 `stid:0`（=不是合集）。
 */
export const nonZero = (value: number | undefined): number | undefined =>
  value === undefined || value === 0 ? undefined : value
