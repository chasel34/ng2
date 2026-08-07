/**
 * BBCode AST → 组件(spec §4:`src/ui` 是渲染器所在层)。
 *
 * ```tsx
 * const nodes = useMemo(() => parseBBCode(floor.content), [floor.content]);
 * <BBCodeBody nodes={nodes} options={{ attachBase: detail.attachBase, postedAt: floor.postedAt }} />
 * ```
 *
 * 03 票节点清单里的每一种标签都有落点:进阶标签(collapse/list/table/dice/album…)
 * 归 08 票补齐,`[dice]` 的点数由调用方用 `resolveDice` 算好后从 `options.dice` 传进来。
 */

export { resolveBBColor, resolveBBSizeScale } from './colors';
export { ContentImage } from './content-image';
export type { BBCodeRenderOptions } from './options';
export { BBCodeBody } from './render';
export { Smiley } from './smiley';
