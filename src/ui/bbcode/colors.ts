/**
 * `[color=…]` / `[size=…]` 的取值归一。
 *
 * NGA 编辑器给的是 CSS 颜色名与百分比字号（官方 `js_bbscode_core.js` 的
 * `ubbcode.fontColor` 24 色 + `ubbcode.fontSize` 五档），但正文里手打什么的都有。
 * 认不出来就返回 undefined —— 让那段文字按默认样式渲染，
 * 而不是把非法值塞进 style 里让 RN 在真机上炸掉。
 */

/**
 * 官方调色板的 24 色（`ubbcode.fontColor`），加上正文里常见但不在面板上的几个。
 * 全是 CSS 颜色关键字，RN 的颜色解析认得，所以只做白名单校验、不做换算。
 */
const NAMED_COLORS = new Set([
  // ubbcode.fontColor 原样 24 色
  'skyblue',
  'royalblue',
  'blue',
  'darkblue',
  'orange',
  'orangered',
  'crimson',
  'red',
  'firebrick',
  'darkred',
  'green',
  'limegreen',
  'seagreen',
  'teal',
  'deeppink',
  'tomato',
  'coral',
  'purple',
  'indigo',
  'burlywood',
  'sandybrown',
  'sienna',
  'chocolate',
  'silver',
  // 面板上没有、但老楼层里常见（white 是「防剧透」的写法，故意让人选中才看得见）
  'white',
  'black',
  'gray',
  'grey',
  'yellow',
  'pink',
  'brown',
  'navy',
  'olive',
  'maroon',
  'cyan',
  'magenta',
  'lightblue',
  'lightgreen',
  'darkgreen',
  'darkorange',
  'gold',
])

const HEX_COLOR = /^#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/

/** 认得出的颜色返回可直接进 style 的值，否则 undefined。 */
export function resolveBBColor(value: string): string | undefined {
  const color = value.trim().toLowerCase()
  if (HEX_COLOR.test(color)) return color
  return NAMED_COLORS.has(color) ? color : undefined
}

/** 字号缩放的上下限：再大撑破楼层卡片，再小认不出字。 */
const MIN_SCALE = 0.6
const MAX_SCALE = 2.5

/**
 * `[size=120%]` → 1.2 倍。官方只发百分比；裸数字按同样的百分比语义处理
 * （`[size=150]` 这种手打形式在老楼层里有）。认不出返回 undefined。
 */
export function resolveBBSizeScale(value: string): number | undefined {
  const match = /^(\d+(?:\.\d+)?)%?$/.exec(value.trim())
  if (match === null) return undefined
  const scale = Number(match[1]) / 100
  if (!Number.isFinite(scale) || scale <= 0) return undefined
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale))
}
