import { BUNDLED_SMILEY_FILES, SMILEY_BASE_URL, SMILEY_CATEGORIES } from './table.generated'
import type { ResolvedSmiley } from './types'

/**
 * 默认套的 key。官方表里它就叫 `0`,`[s:数字]` 查的是它。
 */
const DEFAULT_CATEGORY = '0'

/** key → { label, 名称 → 文件名 },模块加载时建一次。 */
const INDEX: ReadonlyMap<string, { label: string; files: ReadonlyMap<string, string> }> = new Map(
  SMILEY_CATEGORIES.map((category) => [
    category.key,
    { label: category.label, files: new Map(category.entries) },
  ]),
)

const DEFAULT_BUNDLED: ReadonlySet<string> = new Set(BUNDLED_SMILEY_FILES)

export interface ResolveSmileyOptions {
  /**
   * 判定"图片已随包内置"的文件名集合,默认取生成表里实际下载成功的那批。
   * 主要为了单测能构造"表里有、包里没有"的回退场景。
   */
  readonly bundledFiles?: ReadonlySet<string>
}

/**
 * 解析 `[s:...]` 的内容部分,映射到表情资源。
 *
 * 分类与名称的切法照抄 NGA 官方 `js_bbscode_core.js` 的 `[smile]` 分支:
 * 纯数字走默认套;否则 `split(':')` 后取前两段当 `分类`、`名称`,分类为空时退回默认套。
 *
 * 三级兜底:内置图片 → CDN 远程 URL → 原文标记。
 *
 * @param code `[s:` 与 `]` 之间的原文,如 `ac:笑`、`123`
 * @example resolveSmiley('pst:举手') // { kind: 'bundled', file: 'pt00.png', ... }
 */
export function resolveSmiley(code: string, options: ResolveSmileyOptions = {}): ResolvedSmiley {
  const bundled = options.bundledFiles ?? DEFAULT_BUNDLED
  const unresolved = { kind: 'unresolved', raw: `[s:${code}]` } as const

  // 官方用 `parseInt(code,10)` 判定数字套,所以 `[s:0]` 落不进来(取值为假)。
  const asNumber = /^\d+$/.test(code) && Number(code) > 0
  const parts = code.split(':')
  const categoryKey = asNumber ? DEFAULT_CATEGORY : parts[0] || DEFAULT_CATEGORY
  const name = (asNumber ? code : parts[1]) ?? ''

  const category = INDEX.get(categoryKey)
  const file = name ? category?.files.get(name) : undefined
  if (!category || !file) return unresolved

  return {
    kind: bundled.has(file) ? 'bundled' : 'remote',
    category: categoryKey,
    label: category.label,
    name,
    file,
    remoteUrl: `${SMILEY_BASE_URL}/${file}`,
  }
}
