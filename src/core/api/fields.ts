import { unescapeNgaText } from '../bbcode'
import { isRecord } from '../net'

/**
 * 手工遍历 NGA 响应的公共小工具。
 *
 * NGA 的 `data` 里**大量用字符串数字键当数组**（API 文档 §0.6），字段随时可能缺、
 * 可能换类型，自动 JSON 映射全线失效。各接口的解析器都要靠这几个函数把
 * 「取一个字符串」「取一个整数」「按数组顺序遍历」写得短一点。
 */

/**
 * 按数字键升序取键值对；非数字键排在后面，保持原有顺序。
 *
 * **真数组也走这条路**：§0.6 说的「用字符串数字键当数组」只是 `__output=8` 的习惯，
 * `__output=11` 同一个 `__T` 下发的就是货真价实的 JSON 数组。`isRecord` 按约定把数组
 * 排除在外，不单独认一下的话整页主题会静默变成 0 条——`errors.ts` 认 `error` 的数组形态
 * 时踩过同一个坑（2026-08-14，fid=414 打不开的排查）。
 * 数组的 `Object.entries` 给出的正是 `['0', v]` 这样的数字键，后面的排序逻辑原样适用。
 */
export function orderedEntries(value: unknown): (readonly [string, unknown])[] {
  if (!isRecord(value) && !Array.isArray(value)) return []
  return Object.entries(value as Record<string, unknown>)
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

/**
 * 会被 HTML 转义的文本字段（标题这类要直接上屏的）。
 *
 * 服务端不只对正文做转义，`subject` 也一样：实测搜索结果里是
 * `&lt;第六感&gt;那个小孩…`、精华区里是 `1周年&#39;魔力印度&#39;新版本上线`。
 * 正文走 BBCode 解析时已经反转义过，标题不经过那条路径，所以在这里补上——
 * 用的是同一个两轮解码（`core/bbcode/entities`），emoji 的代理对实体也一并还原。
 */
export function text(record: Record<string, unknown>, key: string): string | undefined {
  const raw = str(record, key)
  return raw === undefined ? undefined : unescapeNgaText(raw)
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
