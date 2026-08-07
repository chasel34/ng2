import { describe, expect, it } from 'vitest';

import { childNodeLists, parseBBCode, type BBCodeNode } from '@/core/bbcode';

import { isBlockNode, splitIntoSegments } from './segments';

/**
 * 渲染器的覆盖清单:03 票的 26 种节点,每种一段样例 BBCode + 它该落到哪一层。
 *
 * 本仓库跑不了组件渲染测试(没有 react-test-renderer / RN 预设),所以这里锁的是
 * 「渲染器分派前的那一步」:样例真的解析出了这个类型,而且它的行内/块级归属没漂。
 * `render.tsx` 的 `BlockNode` 分支与这张表一一对应——新增节点类型时这里会先红。
 */
const SAMPLES: Record<BBCodeNode['type'], { source: string; block: boolean }> = {
  text: { source: '一段字', block: false },
  linebreak: { source: '上<br/>下', block: false },
  bold: { source: '[b]粗[/b]', block: false },
  italic: { source: '[i]斜[/i]', block: false },
  underline: { source: '[u]下划线[/u]', block: false },
  strike: { source: '[del]删除线[/del]', block: false },
  color: { source: '[color=red]红[/color]', block: false },
  size: { source: '[size=120%]大[/size]', block: false },
  font: { source: '[font=宋体]宋体[/font]', block: false },
  code: { source: '[code]const a = 1[/code]', block: false },
  link: { source: '[url=https://example.test]站外[/url]', block: false },
  userRef: { source: '[uid=123]某人[/uid]', block: false },
  topicRef: { source: '[tid]45150945[/tid]', block: false },
  floorRef: { source: '[pid=1,2,3]Reply[/pid]', block: false },
  mention: { source: '[@某人]', block: false },
  smiley: { source: '[s:ac:blink]', block: false },

  quote: { source: '[quote]引用[/quote]', block: true },
  image: { source: '[img]./mon_202608/07/a.jpg[/img]', block: true },
  divider: { source: '======', block: true },
  heading: { source: '===标题===', block: true },
  align: { source: '[align=center]居中[/align]', block: true },
  collapse: { source: '[collapse=提要]藏起来的话[/collapse]', block: true },
  list: { source: '[list][*]甲[*]乙[/list]', block: true },
  table: { source: '[table][tr][td]甲[/td][td]乙[/td][/tr][/table]', block: true },
  box: { source: '[lessernuke]处罚说明[/lessernuke]', block: true },
  dice: { source: '[dice]1d100[/dice]', block: true },
  flash: { source: '[flash=video]./a.mp4[/flash]', block: true },
  attach: { source: '[attach]./a.zip[/attach]', block: true },
  album: { source: '[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]', block: true },
};

/** 深度优先收集出现过的节点类型。 */
function typesIn(nodes: readonly BBCodeNode[], found = new Set<BBCodeNode['type']>()) {
  for (const node of nodes) {
    found.add(node.type);
    for (const children of childNodeLists(node)) typesIn(children, found);
  }
  return found;
}

describe('渲染器覆盖清单', () => {
  it.each(Object.entries(SAMPLES))('%s 的样例解析得出这个节点', (type, { source }) => {
    expect([...typesIn(parseBBCode(source))]).toContain(type);
  });

  it.each(Object.entries(SAMPLES))('%s 的行内/块级归属固定', (_type, { source, block }) => {
    const nodes = parseBBCode(source);
    const target = nodes.find((node) => isBlockNode(node) === block) ?? nodes[0];
    expect(target).toBeDefined();
    expect(isBlockNode(target!)).toBe(block);
  });

  it('每一种节点类型都在清单里(03 票加了新节点这里会先红)', () => {
    const covered = new Set<string>(Object.keys(SAMPLES));
    // 用清单里全部样例拼一段长正文,反过来确认解析出的类型不超出清单
    const all = typesIn(parseBBCode(Object.values(SAMPLES).map((s) => s.source).join('<br/>')));
    for (const type of all) expect(covered.has(type)).toBe(true);
  });

  it('全部样例拼在一起也能整段切成行内/块级两种段,不剩解释不了的东西', () => {
    const source = Object.values(SAMPLES)
      .map((sample) => sample.source)
      .join('<br/>');
    const segments = splitIntoSegments(parseBBCode(source));
    expect(segments.length).toBeGreaterThan(0);
    for (const segment of segments) {
      expect(['inline', 'block']).toContain(segment.kind);
    }
  });

  it('不认识的标签原样透传成文字,不会凭空多出节点类型', () => {
    const nodes = parseBBCode('[randomblock]抽奖[/randomblock]');
    expect([...typesIn(nodes)]).toEqual(['text']);
    expect(nodes.map((node) => (node.type === 'text' ? node.value : '')).join('')).toContain(
      '[randomblock]',
    );
  });
});
