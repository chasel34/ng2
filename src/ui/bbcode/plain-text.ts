import { parseBBCode } from '@/core/bbcode';

/**
 * 把一段 BBCode 压成一行纯文本。
 *
 * 给只有一两行位置的地方用——贴条、「我的回复」的摘要:那些正文里常带一整段
 * `[quote][b]Reply to …[/b]` 引用头、图片、表格,连同渲染出来会把那一小块撑爆。
 * 完整形态在楼层里看。
 */
export function plainTextOf(content: string): string {
  const flatten = (nodes: ReturnType<typeof parseBBCode>): string =>
    nodes
      .map((node) => {
        if (node.type === 'text') return node.value;
        if (node.type === 'linebreak') return ' ';
        return 'children' in node ? flatten([...node.children]) : '';
      })
      .join('');
  return flatten(parseBBCode(content)).replace(/\s+/g, ' ').trim();
}
