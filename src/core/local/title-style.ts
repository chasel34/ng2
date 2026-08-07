/**
 * 彩色标题（CONTEXT.md「彩色标题」）——把 `titlefont` / `topic_misc` 解成标题样式。
 *
 * 两个来源、同一套掩码（API 文档 §2 解析要点 4）：
 * - `titlefont` 直接就是掩码（老字段，可能是数字也可能是数字字符串）
 * - `topic_misc` 是 base64（无 padding）的 TLV 串，`type=1` 的那条才是掩码
 *
 * 掩码位与「颜色只取第一个」的优先级照 NGA 官方前端 `commonui.topicMiscVar`
 * 与主题列表渲染那段 else-if 链（https://img4.nga.cn/common_res/js_commonui.js）。
 */

/** 掩码位。1/2/4/8/16 是颜色（互斥取第一个），32/64/128 是字形（可叠加）。 */
const RED = 1
const BLUE = 2
const GREEN = 4
const ORANGE = 8
const SILVER = 16
const BOLD = 32
const ITALIC = 64
const UNDERLINE = 128

/** TLV 的 type：1 = 字体掩码，2 = 合集 stid，3 = 子版块 fid。 */
const TLV_MASK = 1
const TLV_STID = 2
const TLV_SFID = 3
/** 每条记录固定 5 字节：1 字节 type + 4 字节大端无符号整数。 */
const TLV_RECORD_SIZE = 5

export type TitleColor = 'red' | 'blue' | 'green' | 'orange' | 'silver'

export interface TitleStyle {
  /** 没上色时不给这个字段，UI 用正文色 */
  readonly color?: TitleColor
  readonly bold: boolean
  readonly italic: boolean
  readonly underline: boolean
}

/** 没有任何样式的普通标题。 */
export const PLAIN_TITLE_STYLE: TitleStyle = {
  bold: false,
  italic: false,
  underline: false,
}

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

/**
 * base64 解码。RN 里 `atob` 不保证有、core 又不能碰平台 API，所以自己解；
 * NGA 发的是**无 padding** 变体，末尾不足一字节的余位直接丢。
 * 含非法字符返回 undefined——上层当这个字段没写。
 */
function decodeBase64(value: string): Uint8Array | undefined {
  const body = value.replace(/=+$/, '')
  const bytes = new Uint8Array(Math.floor((body.length * 3) / 4))
  let accumulator = 0
  let bits = 0
  let length = 0

  for (const character of body) {
    const index = BASE64_ALPHABET.indexOf(character)
    if (index < 0) return undefined
    accumulator = (accumulator << 6) | index
    bits += 6
    if (bits >= 8) {
      bits -= 8
      bytes[length++] = (accumulator >> bits) & 0xff
    }
  }

  return bytes.subarray(0, length)
}

export interface TopicMisc {
  /** `titlefont` 那套字体掩码 */
  readonly mask?: number
  /** 合集 id */
  readonly stid?: number
  /** 子版块 fid（版块镜像行要靠它跳转） */
  readonly sfid?: number
}

/**
 * 解 `topic_misc`。解不开、空、或以 `~` / `~1` 结尾（官方在这里直接返回空）都给空对象——
 * 这个字段坏掉不该连累整条主题。
 */
export function parseTopicMisc(raw: unknown): TopicMisc {
  if (typeof raw !== 'string' || raw === '' || /~1?$/.test(raw)) return {}
  const bytes = decodeBase64(raw)
  if (bytes === undefined) return {}

  const misc: { mask?: number; stid?: number; sfid?: number } = {}
  for (let at = 0; at + TLV_RECORD_SIZE <= bytes.length; at += TLV_RECORD_SIZE) {
    const type = bytes[at] as number
    // type=0 是串尾（官方那个 while 条件就是靠它跳出）
    if (type === 0) break
    const value =
      ((bytes[at + 1] as number) << 24 >>> 0) +
      ((bytes[at + 2] as number) << 16) +
      ((bytes[at + 3] as number) << 8) +
      (bytes[at + 4] as number)

    if (type === TLV_MASK) misc.mask = value
    else if (type === TLV_STID) misc.stid = value
    else if (type === TLV_SFID) misc.sfid = value
    // 未知 type 照样按 5 字节跳过，官方也这么处理
  }
  return misc
}

/** `titlefont` 偶尔是数字字符串、偶尔是空串。 */
function toMask(raw: unknown): number | undefined {
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : undefined
  if (typeof raw !== 'string' || raw.trim() === '') return undefined
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : undefined
}

/** 掩码 → 样式。颜色位可能同时点亮，官方按 红>蓝>绿>橙>银 只取第一个。 */
export function titleStyleFromMask(mask: number): TitleStyle {
  const color: TitleColor | undefined =
    mask & RED
      ? 'red'
      : mask & BLUE
        ? 'blue'
        : mask & GREEN
          ? 'green'
          : mask & ORANGE
            ? 'orange'
            : mask & SILVER
              ? 'silver'
              : undefined

  return {
    ...(color === undefined ? {} : { color }),
    bold: (mask & BOLD) !== 0,
    italic: (mask & ITALIC) !== 0,
    underline: (mask & UNDERLINE) !== 0,
  }
}

export interface TitleStyleSource {
  /** 主题的 `titlefont` 字段 */
  readonly titlefont?: unknown
  /** 主题的 `topic_misc` 字段 */
  readonly topicMisc?: unknown
}

/**
 * 解标题样式。两个来源都在时以 `topic_misc` 为准：
 * 它是服务端现在实际在发的字段，`titlefont` 常年是空串。
 */
export function decodeTitleStyle(source: TitleStyleSource): TitleStyle {
  const mask = parseTopicMisc(source.topicMisc).mask ?? toMask(source.titlefont)
  return mask === undefined || mask === 0 ? PLAIN_TITLE_STYLE : titleStyleFromMask(mask)
}
