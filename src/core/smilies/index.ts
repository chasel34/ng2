/**
 * core/smilies —— 把 BBCode 的 `[s:分类:名称]` 解析成表情资源。
 * 纯 TS,零 RN 依赖;映射表由 `scripts/fetch-smilies.mjs` 从 NGA 官方前端脚本生成。
 *
 * 三级兜底:随包图片 → CDN 远程 URL → 原样显示原文。渲染侧接法:
 *
 * ```ts
 * const smiley = resolveSmiley(code)
 * if (smiley.kind === 'unresolved') return <Text>{smiley.raw}</Text>
 * const source = SMILEY_ASSETS[smiley.file] ?? { uri: smiley.remoteUrl }
 * ```
 */

export { resolveSmiley, type ResolveSmileyOptions } from './resolve'
export { SMILEY_BASE_URL, SMILEY_CATEGORIES } from './table.generated'
export type { ResolvedSmiley, SmileyCategoryData } from './types'
