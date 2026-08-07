import { describe, expect, it } from 'vitest';

import {
  darkTheme,
  lightTheme,
  radius,
  spacing,
  themes,
  typography,
} from './tokens';

/**
 * 下面三张表逐行照抄 design/project/NGA客户端.dc.html 的「Design Token 表」
 * (T.tokenColors / T.tokenType / T.tokenBox) 与 :root / .omdark 声明块。
 * 改动 tokens.ts 前请先改设计稿,再改这里。
 */

// T.tokenColors —— 设计稿明确列出的 16 色
const DOCUMENTED_COLORS: ReadonlyArray<readonly [keyof typeof lightTheme.colors, string, string]> = [
  ['primary', '#14796B', '#1E9384'],
  ['primaryContainer', '#D8EAE5', '#1E3A35'],
  ['bg', '#FCF4E1', '#1C1C1B'],
  ['surface', '#FFFBF0', '#232322'],
  ['surface2', '#F5F0E0', '#292928'],
  ['menu', '#F7F4EE', '#2E2E2C'],
  ['fg', '#1E1C19', '#E9E7E2'],
  ['fg2', '#57534A', '#B0ACA3'],
  ['meta', '#A39D8E', '#6F6C65'],
  ['link', '#3A5A7A', '#8AA6C8'],
  ['tag', '#B7B0A0', '#5F5C55'],
  ['accent', '#E09A2A', '#D6942E'],
  ['danger', '#DE3B2C', '#E2574C'],
  ['divider', '#EBE3CE', '#333330'],
  ['quote', '#F1EBD6', '#262625'],
  ['scrim', 'rgba(30, 26, 16, 0.42)', 'rgba(0, 0, 0, 0.62)'],
];

// :root / .omdark 里声明、原型 markup 里在用、但没进 token 表的 7 色
const UNDOCUMENTED_COLORS: ReadonlyArray<readonly [keyof typeof lightTheme.colors, string, string]> = [
  ['primaryDark', '#0F5D53', '#17766A'],
  ['onPrimary', '#FFFFFF', '#FFFFFF'],
  ['topbar', '#14796B', '#1C1C1B'],
  ['onTopbar', '#FFFFFF', '#F2F0EB'],
  ['fab', '#14796B', '#1B8377'],
  ['onFab', '#FFFFFF', '#FFFFFF'],
  ['track', '#DCD4BE', '#3A3A36'],
];

describe('颜色 token', () => {
  it.each([...DOCUMENTED_COLORS, ...UNDOCUMENTED_COLORS])(
    '%s 浅色 %s / 深色 %s 与设计稿一致',
    (name, light, dark) => {
      expect(lightTheme.colors[name]).toBe(light);
      expect(darkTheme.colors[name]).toBe(dark);
    },
  );

  it('设计稿明确列出的 16 色全部覆盖,且没有多余的自造色', () => {
    const expected = [...DOCUMENTED_COLORS, ...UNDOCUMENTED_COLORS].map(([name]) => name).sort();
    expect(Object.keys(lightTheme.colors).sort()).toEqual(expected);
    expect(Object.keys(darkTheme.colors).sort()).toEqual(expected);
  });
});

describe('彩色标题 token', () => {
  // 掩码 1/2/4/8/16 各一档;绿是 token 表没有、05 按同明度补的
  it('四档复用 token 表的颜色,只有绿是补的', () => {
    expect(lightTheme.titleColors).toEqual({
      red: lightTheme.colors.danger,
      blue: lightTheme.colors.link,
      green: '#3F8F5B',
      orange: lightTheme.colors.accent,
      silver: lightTheme.colors.meta,
    });
    expect(darkTheme.titleColors).toEqual({
      red: darkTheme.colors.danger,
      blue: darkTheme.colors.link,
      green: '#5FB27C',
      orange: darkTheme.colors.accent,
      silver: darkTheme.colors.meta,
    });
  });
});

describe('阴影 token', () => {
  it('elevation1 / elevation2 与 --shadow / --shadow-2 一致', () => {
    expect(lightTheme.shadows).toEqual({
      elevation1: '0px 2px 12px rgba(70, 58, 24, 0.13)',
      elevation2: '0px 8px 28px rgba(70, 58, 24, 0.2)',
    });
    expect(darkTheme.shadows).toEqual({
      elevation1: '0px 2px 12px rgba(0, 0, 0, 0.45)',
      elevation2: '0px 8px 28px rgba(0, 0, 0, 0.6)',
    });
  });
});

describe('字号 / 行高 token', () => {
  // T.tokenType,行高为设计稿倍数 × 字号
  it('token 表里的六档与设计稿一致', () => {
    expect(typography).toMatchObject({
      title: { fontSize: 18, fontWeight: '600', letterSpacing: 0.2 },
      listTitle: { fontSize: 16, fontWeight: '400', lineHeight: 23.2 },
      body: { fontSize: 15.5, fontWeight: '400', lineHeight: 26.04 },
      tab: { fontSize: 15, fontWeight: '600' },
      note: { fontSize: 12.5, fontWeight: '400', lineHeight: 20.63 },
      meta: { fontSize: 11.5, fontWeight: '400' },
    });
  });

  // token 表没列、但设计稿反复用到的档位(04 补)。页面里不许散写字号,
  // 这一组就是「缺档往这里加」的落点,加一档要在这里登记出处。
  it('补档与设计稿一致', () => {
    expect(typography).toMatchObject({
      // 05 补:主题列表屏(isList)的标题 17/1.45 与信息行 12.5
      topicTitle: { fontSize: 17, fontWeight: '400', lineHeight: 24.65 },
      listMeta: { fontSize: 12.5, fontWeight: '400' },
      gridLabel: { fontSize: 14.5, fontWeight: '400', lineHeight: 19.58 },
      notice: { fontSize: 13.5, fontWeight: '400', lineHeight: 20.25 },
      section: { fontSize: 17, fontWeight: '400' },
      menuItem: { fontSize: 15.5, fontWeight: '400' },
      drawerItem: { fontSize: 15, fontWeight: '400' },
      caption: { fontSize: 12.5, fontWeight: '700', letterSpacing: 0.4 },
      initial: { fontSize: 12, fontWeight: '700' },
      badge: { fontSize: 9, fontWeight: '700' },
    });
  });

  it('没有别的自造档位', () => {
    expect(Object.keys(typography).sort()).toEqual(
      [
        'title',
        'listTitle',
        'body',
        'tab',
        'note',
        'meta',
        'gridLabel',
        'notice',
        'section',
        'menuItem',
        'drawerItem',
        'caption',
        'initial',
        'badge',
        'topicTitle',
        'listMeta',
      ].sort(),
    );
  });
});

describe('圆角 / 间距 token', () => {
  it('圆角档位与设计稿一致', () => {
    expect(radius).toEqual({ sm: 9, md: 12, lg: 14, fab: 19, dialog: 24, full: 999 });
  });

  it('间距档位与设计稿一致', () => {
    expect(spacing).toEqual({ xs: 4, sm: 8, md: 12, lg: 16, xl: 20, row: 14, page: 18 });
  });
});

describe('themes 索引', () => {
  it('按 light / dark 取到对应主题', () => {
    expect(themes.light).toBe(lightTheme);
    expect(themes.dark).toBe(darkTheme);
    expect(lightTheme.scheme).toBe('light');
    expect(darkTheme.scheme).toBe('dark');
  });

  it('两套主题共享同一份字号/圆角/间距', () => {
    expect(lightTheme.typography).toBe(darkTheme.typography);
    expect(lightTheme.radius).toBe(darkTheme.radius);
    expect(lightTheme.spacing).toBe(darkTheme.spacing);
  });
});
