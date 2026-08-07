/**
 * 设计 token —— 唯一颜色/字号/圆角/间距/阴影来源。
 *
 * 全部数值照抄 design/project/NGA客户端.dc.html:
 * 颜色取 :root(浅) 与 .omdark(深) 两个声明块,分档说明取「Design Token 表」。
 * 页面代码一律从这里取值,不允许写魔法值。
 */

export type ColorScheme = 'light' | 'dark';

export interface ColorTokens {
  /** 顶栏 / Tab / FAB / 强调文字 */
  primary: string;
  /** primary 的加深态,用于按下/描边 */
  primaryDark: string;
  /** 版块图标底 / 提示条底 */
  primaryContainer: string;
  /** 落在 primary 上的文字与图标 */
  onPrimary: string;
  /** 页面背景(奶油 / 近黑) */
  bg: string;
  /** 主楼楼层 / 抽屉 / 卡片 */
  surface: string;
  /** 公告条 / 折叠按钮 / 贴条区 */
  surface2: string;
  /** 弹出菜单 / 对话框 */
  menu: string;
  /** 标题 / 正文 */
  fg: string;
  /** 次级正文 / 抽屉图标 */
  fg2: string;
  /** 时间 / 级别 / 威望 */
  meta: string;
  /** 作者名 / 最后回复人 / 链接 */
  link: string;
  /** 标题后的方括号分类 */
  tag: string;
  /** 标签 + 号 / 公告图标 */
  accent: string;
  /** [锁定] / 红色标题 / 删除 */
  danger: string;
  /** 列表分隔线 */
  divider: string;
  /** 引用块底色 */
  quote: string;
  /** 抽屉 / 对话框遮罩 */
  scrim: string;
  /** 顶栏底色:浅色下同 primary,深色下压成近黑 */
  topbar: string;
  /** 顶栏上的文字与图标 */
  onTopbar: string;
  /** FAB 底色 */
  fab: string;
  /** FAB 上的图标 */
  onFab: string;
  /** 进度条 / 滑块的未填充轨道 */
  track: string;
}

const lightColors: ColorTokens = {
  primary: '#14796B',
  primaryDark: '#0F5D53',
  primaryContainer: '#D8EAE5',
  onPrimary: '#FFFFFF',
  bg: '#FCF4E1',
  surface: '#FFFBF0',
  surface2: '#F5F0E0',
  menu: '#F7F4EE',
  fg: '#1E1C19',
  fg2: '#57534A',
  meta: '#A39D8E',
  link: '#3A5A7A',
  tag: '#B7B0A0',
  accent: '#E09A2A',
  danger: '#DE3B2C',
  divider: '#EBE3CE',
  quote: '#F1EBD6',
  scrim: 'rgba(30, 26, 16, 0.42)',
  topbar: '#14796B',
  onTopbar: '#FFFFFF',
  fab: '#14796B',
  onFab: '#FFFFFF',
  track: '#DCD4BE',
};

const darkColors: ColorTokens = {
  primary: '#1E9384',
  primaryDark: '#17766A',
  primaryContainer: '#1E3A35',
  onPrimary: '#FFFFFF',
  bg: '#1C1C1B',
  surface: '#232322',
  surface2: '#292928',
  menu: '#2E2E2C',
  fg: '#E9E7E2',
  fg2: '#B0ACA3',
  meta: '#6F6C65',
  link: '#8AA6C8',
  tag: '#5F5C55',
  accent: '#D6942E',
  danger: '#E2574C',
  divider: '#333330',
  quote: '#262625',
  scrim: 'rgba(0, 0, 0, 0.62)',
  topbar: '#1C1C1B',
  onTopbar: '#F2F0EB',
  fab: '#1B8377',
  onFab: '#FFFFFF',
  track: '#3A3A36',
};

/**
 * 彩色标题(CONTEXT.md「彩色标题」)的五档颜色,对应掩码 1/2/4/8/16。
 *
 * 红/蓝/橙/银直接复用 danger/link/accent/meta——设计稿的 token 表里就是这几档;
 * 只有绿在 token 表里没有对应色,按同明度补一档(浅底压深、深底提亮)。
 */
export interface TitleColorTokens {
  red: string;
  blue: string;
  green: string;
  orange: string;
  silver: string;
}

const lightTitleColors: TitleColorTokens = {
  red: lightColors.danger,
  blue: lightColors.link,
  green: '#3F8F5B',
  orange: lightColors.accent,
  silver: lightColors.meta,
};

const darkTitleColors: TitleColorTokens = {
  red: darkColors.danger,
  blue: darkColors.link,
  green: '#5FB27C',
  orange: darkColors.accent,
  silver: darkColors.meta,
};

export interface ShadowTokens {
  /** 卡片 / 提示条 */
  elevation1: string;
  /** FAB / 菜单 / 抽屉 */
  elevation2: string;
}

const lightShadows: ShadowTokens = {
  elevation1: '0px 2px 12px rgba(70, 58, 24, 0.13)',
  elevation2: '0px 8px 28px rgba(70, 58, 24, 0.2)',
};

const darkShadows: ShadowTokens = {
  elevation1: '0px 2px 12px rgba(0, 0, 0, 0.45)',
  elevation2: '0px 8px 28px rgba(0, 0, 0, 0.6)',
};

/**
 * Design Token 表列出的六档字号。行高按设计稿倍数换算成 RN 需要的绝对像素;
 * 设计稿未标行高的档位不给 lineHeight,交给系统默认。
 */
const tokenTableTypography = {
  /** 顶栏标题 18 · 600 */
  title: { fontSize: 18, fontWeight: '600', letterSpacing: 0.2 },
  /** 列表主题标题 16 · 1.45 */
  listTitle: { fontSize: 16, fontWeight: '400', lineHeight: 23.2 },
  /** 楼层正文 15.5 · 1.68 */
  body: { fontSize: 15.5, fontWeight: '400', lineHeight: 26.04 },
  /** Tab / 分段控件 15 · 600 */
  tab: { fontSize: 15, fontWeight: '600' },
  /** 贴条 12.5 · 1.65 */
  note: { fontSize: 12.5, fontWeight: '400', lineHeight: 20.63 },
  /** 级别 / 威望 / 时间 11.5 */
  meta: { fontSize: 11.5, fontWeight: '400' },
} as const;

/**
 * Design Token 表没列、但设计稿反复用到的档位(04 首页铺真实页面时补,见 01 票遗留问题 3)。
 * 页面里不许再散写字号,缺档就往这里加并注明设计稿出处。
 */
const designOnlyTypography = {
  /** 版块宫格里的版块名 14.5 · 1.35 */
  gridLabel: { fontSize: 14.5, fontWeight: '400', lineHeight: 19.58 },
  /**
   * 主题列表的标题 17 · 1.45。
   * Design Token 表里「列表主题标题」标的是 16——那是我的主题/收藏夹那类二级列表的字号,
   * 主题列表屏(design 稿 `isList`)实际用的是 17,这里按屏取值。
   */
  topicTitle: { fontSize: 17, fontWeight: '400', lineHeight: 24.65 },
  /** 主题行信息行 / 子版块 tag / 底部载入提示 12.5 */
  listMeta: { fontSize: 12.5, fontWeight: '400' },
  /** 公告条 / 对话框正文 13.5 · 1.5 */
  notice: { fontSize: 13.5, fontWeight: '400', lineHeight: 20.25 },
  /** 分组标题 / 二级页顶栏标题 17 */
  section: { fontSize: 17, fontWeight: '400' },
  /** 弹出菜单条目 15.5 */
  menuItem: { fontSize: 15.5, fontWeight: '400' },
  /** 抽屉条目 15 */
  drawerItem: { fontSize: 15, fontWeight: '400' },
  /** 抽屉分区小标题 12.5 · 700 */
  caption: { fontSize: 12.5, fontWeight: '700', letterSpacing: 0.4 },
  /** 版块图标的首字占位 12 · 700 */
  initial: { fontSize: 12, fontWeight: '700' },
  /** 分组标题前的圆形角标 9 · 700 */
  badge: { fontSize: 9, fontWeight: '700' },
} as const;

export const typography = { ...tokenTableTypography, ...designOnlyTypography } as const;

/** 圆角档位。sm 取设计稿 8–9 区间的上界(token 表色块用的就是 9)。 */
export const radius = {
  /** 分类标签 / 页码格 */
  sm: 9,
  /** 提示条 / 引用块 / 折叠按钮 */
  md: 12,
  /** 菜单 / 卡片 / Snackbar */
  lg: 14,
  fab: 19,
  dialog: 24,
  /** 圆形图标按钮(设计稿里是 46/44 见方配 23/22 圆角) */
  full: 999,
} as const;

/** 间距档位 4·8·12·16·20,外加设计稿反复出现的列表行 14 与页面左右留白 18。 */
export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  row: 14,
  page: 18,
} as const;

export interface Theme {
  scheme: ColorScheme;
  colors: ColorTokens;
  titleColors: TitleColorTokens;
  shadows: ShadowTokens;
  typography: typeof typography;
  radius: typeof radius;
  spacing: typeof spacing;
}

export const lightTheme: Theme = {
  scheme: 'light',
  colors: lightColors,
  titleColors: lightTitleColors,
  shadows: lightShadows,
  typography,
  radius,
  spacing,
};

export const darkTheme: Theme = {
  scheme: 'dark',
  colors: darkColors,
  titleColors: darkTitleColors,
  shadows: darkShadows,
  typography,
  radius,
  spacing,
};

export const themes: Record<ColorScheme, Theme> = {
  light: lightTheme,
  dark: darkTheme,
};
