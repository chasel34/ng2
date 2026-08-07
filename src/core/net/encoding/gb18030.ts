import {
  GB18030_RANGE_CODE_POINTS,
  GB18030_RANGE_POINTERS,
  GB18030_TWO_BYTE_INDEX,
} from './gb18030-index'

/**
 * 纯 JS 的 GB18030（含 GBK 子集）编解码。
 *
 * 设备端不能用 `new TextDecoder('gb18030')`：Hermes 只实现了 utf-8 这一种编码，
 * 传别的标签会抛 RangeError。所以这里自带 WHATWG 索引表跑解码状态机。
 * 单测里用 Node 的 TextDecoder('gb18030') 对拍全部 23940 个双字节序列与
 * 全部四字节 pointer，保证行为与标准实现逐字节一致。
 */

/** 解码失败时产出的 U+FFFD。 */
export const REPLACEMENT_CHAR = '�'

const REPLACEMENT = REPLACEMENT_CHAR

/** 四字节序列的 pointer → 码点；无映射返回 null。 */
function rangesCodePoint(pointer: number): number | null {
  if ((pointer > 39419 && pointer < 189000) || pointer > 1237575) return null
  if (pointer >= 189000) return pointer - 189000 + 0x10000

  // 游程表按 pointer 升序，二分找 pointer 所在的那一段
  let lo = 0
  let hi = GB18030_RANGE_POINTERS.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (GB18030_RANGE_POINTERS[mid]! <= pointer) lo = mid
    else hi = mid - 1
  }
  const base = GB18030_RANGE_CODE_POINTS[lo]!
  if (base < 0) return null
  return base + (pointer - GB18030_RANGE_POINTERS[lo]!)
}

/** 双字节 pointer → 码点；越界返回 -1。索引表无空洞，故 -1 只可能来自越界。 */
function twoByteCodePoint(pointer: number): number {
  const cp = GB18030_TWO_BYTE_INDEX.charCodeAt(pointer)
  return Number.isNaN(cp) ? -1 : cp
}

/**
 * 按 WHATWG gb18030 解码器解码；非法序列产出 U+FFFD，永不抛错。
 */
export function decodeGb18030(bytes: Uint8Array): string {
  let out = ''
  let first = 0
  let second = 0
  let third = 0
  let i = 0

  while (true) {
    if (i >= bytes.length) {
      // 流末尾还留着未消费的前导字节 → 一个替换字符
      if (first !== 0 || second !== 0 || third !== 0) out += REPLACEMENT
      break
    }
    const byte = bytes[i]!

    if (third !== 0) {
      if (byte < 0x30 || byte > 0x39) {
        // 回退 second/third/byte 重新解析（second 在 i-2）
        i -= 2
        first = second = third = 0
        out += REPLACEMENT
        continue
      }
      const pointer =
        (first - 0x81) * 12600 +
        (second - 0x30) * 1260 +
        (third - 0x81) * 10 +
        (byte - 0x30)
      first = second = third = 0
      i += 1
      const cp = rangesCodePoint(pointer)
      out += cp === null ? REPLACEMENT : String.fromCodePoint(cp)
      continue
    }

    if (second !== 0) {
      if (byte >= 0x81 && byte <= 0xfe) {
        third = byte
        i += 1
        continue
      }
      // 回退 second/byte 重新解析（second 在 i-1）
      i -= 1
      first = second = 0
      out += REPLACEMENT
      continue
    }

    if (first !== 0) {
      if (byte >= 0x30 && byte <= 0x39) {
        second = byte
        i += 1
        continue
      }
      const lead = first
      first = 0
      let cp = -1
      if ((byte >= 0x40 && byte <= 0x7e) || (byte >= 0x80 && byte <= 0xfe)) {
        const offset = byte < 0x7f ? 0x40 : 0x41
        cp = twoByteCodePoint((lead - 0x81) * 190 + (byte - offset))
      }
      if (cp >= 0) {
        out += String.fromCharCode(cp)
        i += 1
        continue
      }
      out += REPLACEMENT
      // ASCII 尾字节退回流里当普通字符重新解析
      if (byte > 0x7f) i += 1
      continue
    }

    if (byte <= 0x7f) {
      out += String.fromCharCode(byte)
      i += 1
      continue
    }
    if (byte === 0x80) {
      out += '€'
      i += 1
      continue
    }
    if (byte <= 0xfe) {
      first = byte
      i += 1
      continue
    }
    out += REPLACEMENT
    i += 1
  }

  return out
}

/** 码点 → 双字节 pointer 的反查表，首次编码时才建。 */
let encodeIndex: Map<number, number> | null = null

function getEncodeIndex(): Map<number, number> {
  if (encodeIndex) return encodeIndex
  const map = new Map<number, number>()
  for (let pointer = 0; pointer < GB18030_TWO_BYTE_INDEX.length; pointer++) {
    const cp = GB18030_TWO_BYTE_INDEX.charCodeAt(pointer)
    // 索引里有重复码点，取第一个 pointer（与 WHATWG「index pointer」一致）
    if (!map.has(cp)) map.set(cp, pointer)
  }
  encodeIndex = map
  return map
}

const ASCII_UNRESERVED = /[A-Za-z0-9\-_.!~*'()]/

function percent(byte: number): string {
  return '%' + byte.toString(16).toUpperCase().padStart(2, '0')
}

function encodeAscii(char: string): string {
  return ASCII_UNRESERVED.test(char) ? char : percent(char.charCodeAt(0))
}

/**
 * 按 GBK 编码后再 percent-encode，用于 NGA 那些吃 GBK 参数的接口
 * （`thread.php` 的 `author`、`forum.php` 的 `key`、`nuke.php` 的 `username` 等）。
 *
 * GBK 表里没有的字符（emoji 等）不丢弃，按 API 文档 §0.5 的转义约定写成
 * **UTF-16 码元的十进制 HTML 实体**——码点 > 0xFFFF 要拆成代理对两个实体，
 * 例：`"😂"` → `&#55357;&#56834;`。
 */
export function gbkEncodeURIComponent(text: string): string {
  const index = getEncodeIndex()
  let out = ''
  // 按 UTF-16 码元遍历：表外字符本来就要按码元转实体
  for (let i = 0; i < text.length; i++) {
    const unit = text.charCodeAt(i)
    if (unit <= 0x7f) {
      out += encodeAscii(text[i]!)
      continue
    }
    const pointer = index.get(unit)
    if (pointer === undefined) {
      for (const entityChar of `&#${unit};`) out += encodeAscii(entityChar)
      continue
    }
    const lead = Math.floor(pointer / 190) + 0x81
    const trailIndex = pointer % 190
    const trail = trailIndex < 0x3f ? trailIndex + 0x40 : trailIndex + 0x41
    out += percent(lead) + percent(trail)
  }
  return out
}
