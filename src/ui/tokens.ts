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
 * 字号档位。行高按设计稿倍数换算成 RN 需要的绝对像素;
 * 设计稿未标行高的档位不给 lineHeight,交给系统默认。
 */
export const typography = {
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
  shadows: ShadowTokens;
  typography: typeof typography;
  radius: typeof radius;
  spacing: typeof spacing;
}

export const lightTheme: Theme = {
  scheme: 'light',
  colors: lightColors,
  shadows: lightShadows,
  typography,
  radius,
  spacing,
};

export const darkTheme: Theme = {
  scheme: 'dark',
  colors: darkColors,
  shadows: darkShadows,
  typography,
  radius,
  spacing,
};

export const themes: Record<ColorScheme, Theme> = {
  light: lightTheme,
  dark: darkTheme,
};
