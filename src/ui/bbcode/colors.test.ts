import { describe, expect, it } from 'vitest';

import { resolveBBColor, resolveBBSizeScale } from './colors';

describe('resolveBBColor', () => {
  it('认官方调色板的 24 个颜色名', () => {
    expect(resolveBBColor('skyblue')).toBe('skyblue');
    expect(resolveBBColor('crimson')).toBe('crimson');
    expect(resolveBBColor('sandybrown')).toBe('sandybrown');
  });

  it('大小写与空白都收', () => {
    expect(resolveBBColor(' Red ')).toBe('red');
  });

  it('认十六进制色值', () => {
    expect(resolveBBColor('#FF0000')).toBe('#ff0000');
    expect(resolveBBColor('#f00')).toBe('#f00');
    expect(resolveBBColor('#ff000080')).toBe('#ff000080');
  });

  it('认不出的一律返回 undefined，不把脏值塞进 style', () => {
    for (const bad of ['', 'rgb(1,2,3)', 'javascript:x', '#gg0000', '红色', '#ff00']) {
      expect(resolveBBColor(bad)).toBeUndefined();
    }
  });
});

describe('resolveBBSizeScale', () => {
  it('百分比换算成倍数', () => {
    expect(resolveBBSizeScale('100%')).toBe(1);
    expect(resolveBBSizeScale('150%')).toBe(1.5);
  });

  it('裸数字按同样的百分比语义', () => {
    expect(resolveBBSizeScale('120')).toBe(1.2);
  });

  it('过大过小都夹到上下限，免得撑破楼层卡片', () => {
    expect(resolveBBSizeScale('900%')).toBe(2.5);
    expect(resolveBBSizeScale('10%')).toBe(0.6);
  });

  it('认不出返回 undefined', () => {
    for (const bad of ['', 'large', '-50%', '0%', '12px']) {
      expect(resolveBBSizeScale(bad)).toBeUndefined();
    }
  });
});
