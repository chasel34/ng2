import type { BBCodeNode } from '@/core/bbcode';

/**
 * 排版分段:把一串 AST 节点切成「行内段」与「块级节点」交替的序列。
 *
 * RN 的 `<Text>` 里塞不进 `<View>`(Android 上会直接不显示),所以图片、引用块、
 * 分割线这些必须自己占一行的东西得先从行内流里摘出来,各占一个容器。
 *
 * 纯函数、不碰组件,这样这套判断能单测——本仓库跑不了组件渲染测试。
 */

/** 会自己占一行、塞不进 `<Text>` 的节点。 */
const BLOCK_TYPES = new Set<BBCodeNode['type']>(['quote', 'image', 'divider', 'heading']);

export const isBlockNode = (node: BBCodeNode): boolean => BLOCK_TYPES.has(node.type);

/**
 * 一个节点里(含各层后代)有没有必须自己占一行的东西。
 *
 * 只看顶层是不够的:NGA 上 `[align=center][img]…[/img][/align]`、`[b][img]…[/b]`
 * 这种**图片裹在行内标签里**的写法极常见,漏判就会把那张图塞进 `<Text>` 然后整个消失。
 */
export function containsBlock(node: BBCodeNode): boolean {
  if (isBlockNode(node)) return true;
  if ('children' in node) return node.children.some(containsBlock);
  if (node.type === 'list') return node.items.some((item) => item.some(containsBlock));
  if (node.type === 'table') {
    return node.rows.some((row) => row.cells.some((cell) => cell.children.some(containsBlock)));
  }
  return false;
}

export type Segment =
  | { readonly kind: 'inline'; readonly nodes: readonly BBCodeNode[] }
  /** 块级节点;可能是行内标签裹着块级内容,由渲染层递归展开 */
  | { readonly kind: 'block'; readonly node: BBCodeNode };

/** 切段。裹着块级内容的行内标签一并升格成块。 */
export function splitIntoSegments(nodes: readonly BBCodeNode[]): Segment[] {
  const segments: Segment[] = [];
  let inline: BBCodeNode[] = [];

  const flush = () => {
    // 只剩换行的段不值得占一个 <Text>:块级元素之间本来就有间距
    if (inline.some((node) => node.type !== 'linebreak')) {
      segments.push({ kind: 'inline', nodes: inline });
    }
    inline = [];
  };

  for (const node of nodes) {
    if (containsBlock(node)) {
      flush();
      segments.push({ kind: 'block', node });
    } else {
      inline.push(node);
    }
  }
  flush();
  return segments;
}
