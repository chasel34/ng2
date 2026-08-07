/**
 * BBCode AST → 组件(spec §4:`src/ui` 是渲染器所在层)。
 *
 * ```tsx
 * const nodes = useMemo(() => parseBBCode(floor.content), [floor.content]);
 * <BBCodeBody nodes={nodes} options={{ attachBase: detail.attachBase, postedAt: floor.postedAt }} />
 * ```
 *
 * 本票只覆盖基础标签;进阶标签(collapse/table/dice/vote…)是 08 票的事,
 * 现在按「不丢内容」降级渲染。
 */

export { resolveBBColor, resolveBBSizeScale } from './colors';
export { ContentImage } from './content-image';
export { BBCodeBody, type BBCodeRenderOptions } from './render';
export { Smiley } from './smiley';
