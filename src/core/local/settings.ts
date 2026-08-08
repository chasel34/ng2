import { NGA_HOSTS, DEFAULT_NGA_HOST } from '../net/constants'

/**
 * 设置项的取值与默认值（22 号票的设置三屏）。
 *
 * 放 core 是因为这里全是纯规则：默认值表、滑杆的量化与格式化、坏存档的回落。
 * 设备侧（`store/settings.ts`）只负责把 `AppSettings` 塞进 MMKV 再读回来。
 *
 * 不在这里的两组设置：
 * - 夜间模式档位在 `store/theme.ts`（05 就建好了，UI 层直接消费）；
 * - `readPhpWindowsPhoneUa` / `webFallbackMode` 在 `store/net-settings.ts`（18、19 票）。
 *   它们要被 `core/net` 的策略链每次请求现取，跟着请求层走比跟着设置表走更合适。
 */

/** 正文图片按哪一档清晰度取（设计稿「图片加载策略」）。 */
export type ImageQuality = 'original' | 'smart' | 'thumbnail'

export const IMAGE_QUALITY_LABELS: Readonly<Record<ImageQuality, string>> = {
  original: '总是原图',
  smart: '智能（Wi-Fi 原图 / 流量缩略图）',
  thumbnail: '总是缩略图',
}

/**
 * 主题风格（设计稿「主题风格」对话框的前两项）。
 *
 * 第三项「夜间近黑」在设计稿里的副标题就写着「跟随夜间模式开关」——它不是第三套配色，
 * 而是夜间模式本身，所以不进这个联合类型，选它等于把夜间模式打开。
 */
export type ThemeStyle = 'ink' | 'plain'

export const THEME_STYLE_LABELS: Readonly<Record<ThemeStyle, string>> = {
  ink: '墨绿（NGA 经典）',
  plain: '纯白',
}

/** 字体与头像大小(设计稿「字体和头像大小」屏的五根滑杆)。 */
export interface AppearanceSettings {
  /** 主题列表的标题字号 */
  listFontSize: number
  /** 头像大小,百分比(100 = token 里的 42) */
  avatarScale: number
  /** 表情大小,百分比(150 = 现行的 24 高) */
  smileyScale: number
  /** 楼层正文字号 */
  bodyFontSize: number
  /** 楼层正文行高倍数 */
  bodyLineHeight: number
}

export interface AppSettings {
  /** 请求用的 NGA 域名，必须是 `NGA_HOSTS` 里的一个 */
  host: string
  /** 左手模式：FAB 与菜单移到左侧。22 票只存值（见票面 Comments） */
  leftHanded: boolean
  /** 主题列表与详情页用纯色底（surface）而不是奶油底（bg） */
  solidBackground: boolean
  /** 被喷提示：关掉后通知不再轮询，抽屉也不显示未读角标 */
  sprayNotice: boolean
  /** 提示声音。没有声音钩子可接，22 票只存值（见票面 Comments） */
  noticeSound: boolean
  themeStyle: ThemeStyle
  /** 滚到底自动翻下一页 */
  autoLoadNextPage: boolean
  /** 移动网络下不自动拉图，正文图与附件都折成「点击显示」 */
  wifiOnlyImages: boolean
  /** 楼层里显示签名档 */
  showSignature: boolean
  /** 页码条移到屏幕底部 */
  bottomPageBar: boolean
  imageQuality: ImageQuality
  /** 从左边缘右滑返回上一页 */
  gestureBack: boolean
  /** 读帖时屏幕常亮 */
  keepScreenOn: boolean
  appearance: AppearanceSettings
}

/**
 * 默认值。开关档位照设计稿 `SWDEF`，只有三项按本项目的现状取值：
 *
 * - `solidBackground`：设计稿画的是开，但本项目的奶油底 `bg` 就是 M2 验收过的样子，
 *   默认开等于一上来就换掉已验收的外观，所以默认关；
 * - `keepScreenOn`：同设计稿（关）；
 * - `appearance` 五档：设计稿写的是它自己 mock 的当前值（列表 18、头像 104、正文 15.5…），
 *   这里取 `ui/tokens.ts` 的真实档位，好让「重置」回到的正是 app 现在的样子。
 */
export const DEFAULT_SETTINGS: AppSettings = {
  host: DEFAULT_NGA_HOST,
  leftHanded: false,
  solidBackground: false,
  sprayNotice: true,
  noticeSound: true,
  themeStyle: 'ink',
  autoLoadNextPage: true,
  wifiOnlyImages: true,
  showSignature: true,
  bottomPageBar: false,
  imageQuality: 'smart',
  gestureBack: true,
  keepScreenOn: false,
  appearance: {
    listFontSize: 17,
    avatarScale: 100,
    smileyScale: 150,
    bodyFontSize: 15.5,
    bodyLineHeight: 1.68,
  },
}

/** 头像百分比的基准值(`ui/avatar.tsx` 的 42)。 */
export const AVATAR_BASE_SIZE = 42

/** 表情百分比的基准值:150% = `ui/bbcode/smiley.tsx` 现行的 24 高。 */
export const SMILEY_BASE_HEIGHT = 16

export interface SliderSpec {
  readonly key: keyof AppearanceSettings
  readonly label: string
  readonly min: number
  readonly max: number
  readonly step: number
  /** 显示与量化保留的小数位;整数档为 0 */
  readonly decimals: number
  /** 值旁边的单位后缀,没有就留空 */
  readonly unit: string
}

/** 五根滑杆的量程与步长照抄设计稿 `T.fontSliders`。 */
export const APPEARANCE_SLIDERS: readonly SliderSpec[] = [
  { key: 'listFontSize', label: '帖子列表字体大小', min: 12, max: 26, step: 1, decimals: 0, unit: '' },
  { key: 'avatarScale', label: '头像大小', min: 60, max: 160, step: 4, decimals: 0, unit: '%' },
  { key: 'smileyScale', label: '表情大小', min: 80, max: 220, step: 10, decimals: 0, unit: '%' },
  { key: 'bodyFontSize', label: '帖子内字体大小', min: 12, max: 22, step: 0.5, decimals: 1, unit: '' },
  { key: 'bodyLineHeight', label: '主题详情页行高', min: 1.3, max: 2.2, step: 0.02, decimals: 2, unit: '' },
]

/** 量化到步长并夹进量程。浮点步长(行高 0.02)必须按小数位收尾，否则会攒出 1.7000000000000002。 */
export function clampSlider(spec: SliderSpec, value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_SETTINGS.appearance[spec.key]
  const steps = Math.round((value - spec.min) / spec.step)
  const snapped = spec.min + steps * spec.step
  const bounded = Math.min(spec.max, Math.max(spec.min, snapped))
  return Number(bounded.toFixed(spec.decimals))
}

/** 滑杆填充比例 0–1(轨道与气泡的位置都按它算)。 */
export function sliderRatio(spec: SliderSpec, value: number): number {
  const ratio = (value - spec.min) / (spec.max - spec.min)
  return Math.min(1, Math.max(0, ratio))
}

/** 把 0–1 的落点换回设置值(拖动手势报的就是比例)。 */
export function sliderValueAt(spec: SliderSpec, ratio: number): number {
  return clampSlider(spec, spec.min + ratio * (spec.max - spec.min))
}

/** 气泡里显示的值,整数档不带小数点。 */
export function formatSliderValue(spec: SliderSpec, value: number): string {
  return `${value.toFixed(spec.decimals)}${spec.unit}`
}

/** 百分比档换算成实际像素。 */
export const avatarSizeOf = (scale: number): number => Math.round((AVATAR_BASE_SIZE * scale) / 100)
export const smileyHeightOf = (scale: number): number =>
  Math.round((SMILEY_BASE_HEIGHT * scale) / 100)

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

function pickBoolean(raw: Record<string, unknown>, key: keyof AppSettings, fallback: boolean) {
  const value = raw[key]
  return typeof value === 'boolean' ? value : fallback
}

/**
 * 把存档还原成设置表。**逐项回落**而不是整份作废：加了新设置项的版本读旧存档时，
 * 老项该留着；某一项被写坏了也只丢那一项。
 */
export function parseSettings(raw: unknown): AppSettings {
  if (!isRecord(raw)) return DEFAULT_SETTINGS
  const host = raw['host']
  const themeStyle = raw['themeStyle']
  const imageQuality = raw['imageQuality']
  return {
    host:
      typeof host === 'string' && (NGA_HOSTS as readonly string[]).includes(host)
        ? host
        : DEFAULT_SETTINGS.host,
    leftHanded: pickBoolean(raw, 'leftHanded', DEFAULT_SETTINGS.leftHanded),
    solidBackground: pickBoolean(raw, 'solidBackground', DEFAULT_SETTINGS.solidBackground),
    sprayNotice: pickBoolean(raw, 'sprayNotice', DEFAULT_SETTINGS.sprayNotice),
    noticeSound: pickBoolean(raw, 'noticeSound', DEFAULT_SETTINGS.noticeSound),
    themeStyle:
      themeStyle === 'ink' || themeStyle === 'plain' ? themeStyle : DEFAULT_SETTINGS.themeStyle,
    autoLoadNextPage: pickBoolean(raw, 'autoLoadNextPage', DEFAULT_SETTINGS.autoLoadNextPage),
    wifiOnlyImages: pickBoolean(raw, 'wifiOnlyImages', DEFAULT_SETTINGS.wifiOnlyImages),
    showSignature: pickBoolean(raw, 'showSignature', DEFAULT_SETTINGS.showSignature),
    bottomPageBar: pickBoolean(raw, 'bottomPageBar', DEFAULT_SETTINGS.bottomPageBar),
    imageQuality:
      imageQuality === 'original' || imageQuality === 'smart' || imageQuality === 'thumbnail'
        ? imageQuality
        : DEFAULT_SETTINGS.imageQuality,
    gestureBack: pickBoolean(raw, 'gestureBack', DEFAULT_SETTINGS.gestureBack),
    keepScreenOn: pickBoolean(raw, 'keepScreenOn', DEFAULT_SETTINGS.keepScreenOn),
    appearance: parseAppearance(raw['appearance']),
  }
}

function parseAppearance(raw: unknown): AppearanceSettings {
  if (!isRecord(raw)) return DEFAULT_SETTINGS.appearance
  const next = { ...DEFAULT_SETTINGS.appearance }
  for (const spec of APPEARANCE_SLIDERS) {
    const value = raw[spec.key]
    if (typeof value === 'number') next[spec.key] = clampSlider(spec, value)
  }
  return next
}
