import { describe, expect, it } from 'vitest';

import { parseBBCode } from '@/core/bbcode';

import { containsBlock, splitIntoSegments } from './segments';

const segmentsOf = (source: string) => splitIntoSegments(parseBBCode(source));

describe('splitIntoSegments', () => {
  it('纯文字只有一段行内', () => {
    const segments = segmentsOf('第一行<br/>第二行');
    expect(segments).toHaveLength(1);
    expect(segments[0]?.kind).toBe('inline');
  });

  it('图片从行内流里摘出来单独占一块', () => {
    const segments = segmentsOf('看图<br/>[img]./mon_202608/07/a.jpg[/img]<br/>就这样');
    expect(segments.map((segment) => segment.kind)).toEqual(['inline', 'block', 'inline']);
  });

  it('引用块与分割线也是块', () => {
    expect(segmentsOf('[quote]引用[/quote]')[0]?.kind).toBe('block');
    expect(segmentsOf('======')[0]?.kind).toBe('block');
  });

  it('只有换行的段不单独成段', () => {
    const segments = segmentsOf('[img]./a.jpg[/img]<br/><br/>[img]./b.jpg[/img]');
    expect(segments.map((segment) => segment.kind)).toEqual(['block', 'block']);
  });
});

describe('裹在行内标签里的块级内容', () => {
  /**
   * 这一组是回归锁。只按「节点自己是不是块级」切段的话,下面这些写法里的图片
   * 会被塞进 `<Text>`,在 Android 上直接不显示——而它们在 NGA 上极常见。
   */
  it.each([
    ['居中图片', '[align=center][img]./mon_202608/07/a.jpg[/img][/align]'],
    ['加粗裹图片', '[b][img]./mon_202608/07/a.jpg[/img][/b]'],
    ['颜色裹引用', '[color=red][quote]引用[/quote][/color]'],
    ['折叠里的图片', '[collapse=看图][img]./mon_202608/07/a.jpg[/img][/collapse]'],
    ['列表项里的图片', '[list][*][img]./mon_202608/07/a.jpg[/img][/list]'],
    ['表格单元格里的图片', '[table][tr][td][img]./mon_202608/07/a.jpg[/img][/td][/tr][/table]'],
    ['套两层', '[b][color=red][img]./mon_202608/07/a.jpg[/img][/color][/b]'],
  ])('%s 升格成块,不会被吞掉', (_name, source) => {
    const segments = segmentsOf(source);
    expect(segments).toHaveLength(1);
    expect(segments[0]?.kind).toBe('block');
  });

  it('没裹块级内容的行内标签仍然留在行内', () => {
    for (const source of ['[b]粗[/b]', '[color=red]红[/color]', '[size=120%]大字[/size]']) {
      expect(segmentsOf(source).every((segment) => segment.kind === 'inline')).toBe(true);
    }
  });
});

describe('自带框或需要交互的进阶标签', () => {
  /**
   * 这些标签哪怕里面只有一行字也得占一块:对齐要作用在容器上、折叠块要有开关、
   * 表格要能横向滚、骰子和媒体是卡片——留在 `<Text>` 里这些都做不到。
   */
  it.each([
    ['居中的一行字', '[align=center]居中字[/align]'],
    ['折叠块', '[collapse=提要]内容[/collapse]'],
    ['列表', '[list][*]甲[*]乙[/list]'],
    ['表格', '[table][tr][td]甲[/td][td]乙[/td][/tr][/table]'],
    ['版规警告块', '[lessernuke]内容[/lessernuke]'],
    ['骰子', '[dice]1d100[/dice]'],
    ['视频', '[flash=video]./a.mp4[/flash]'],
    ['附件', '[attach]./a.zip[/attach]'],
    ['相册', '[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]'],
    ['标题', '[h]小标题[/h]'],
  ])('%s 单独占一块', (_name, source) => {
    const segments = segmentsOf(source);
    expect(segments).toHaveLength(1);
    expect(segments[0]?.kind).toBe('block');
  });
});

describe('containsBlock', () => {
  it('递归看到任意深度', () => {
    const [node] = parseBBCode('[b][i][u][img]./a.jpg[/img][/u][/i][/b]');
    expect(node).toBeDefined();
    expect(containsBlock(node!)).toBe(true);
  });

  it('纯文字不算', () => {
    const [node] = parseBBCode('就是一段字');
    expect(containsBlock(node!)).toBe(false);
  });
});
