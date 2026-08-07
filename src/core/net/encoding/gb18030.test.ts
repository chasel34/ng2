import { describe, expect, it } from 'vitest'
import { decodeGb18030, gbkEncodeURIComponent } from './gb18030'
import { GB18030_TWO_BYTE_INDEX } from './gb18030-index'

// Node 的 TextDecoder 支持 gb18030，设备端的 Hermes 不支持——用它给纯 JS 实现对拍。
const reference = new TextDecoder('gb18030')

function twoByteSequence(pointer: number): Uint8Array {
  const lead = 0x81 + Math.floor(pointer / 190)
  const trailIndex = pointer % 190
  const trail = trailIndex < 0x3f ? 0x40 + trailIndex : 0x41 + trailIndex
  return new Uint8Array([lead, trail])
}

function fourByteSequence(pointer: number): Uint8Array {
  return new Uint8Array([
    0x81 + Math.floor(pointer / 12600),
    0x30 + (Math.floor(pointer / 1260) % 10),
    0x81 + (Math.floor(pointer / 10) % 126),
    0x30 + (pointer % 10),
  ])
}

describe('decodeGb18030', () => {
  it('与 Node TextDecoder 对拍全部 23940 个双字节序列', () => {
    const mismatches: string[] = []
    for (let pointer = 0; pointer < GB18030_TWO_BYTE_INDEX.length; pointer++) {
      const bytes = twoByteSequence(pointer)
      const actual = decodeGb18030(bytes)
      const expected = reference.decode(bytes)
      if (actual !== expected) mismatches.push(`pointer ${pointer}: ${actual} != ${expected}`)
    }
    expect(mismatches).toEqual([])
  })

  it('与 Node TextDecoder 对拍全部 BMP 段四字节序列', () => {
    const mismatches: string[] = []
    for (let pointer = 0; pointer < 39420; pointer++) {
      const bytes = fourByteSequence(pointer)
      const actual = decodeGb18030(bytes)
      const expected = reference.decode(bytes)
      if (actual !== expected) mismatches.push(`pointer ${pointer}: ${actual} != ${expected}`)
    }
    expect(mismatches).toEqual([])
  })

  it('解出星际平面码点', () => {
    for (const pointer of [189000, 189001, 500000, 1237575]) {
      const bytes = fourByteSequence(pointer)
      expect(decodeGb18030(bytes)).toBe(reference.decode(bytes))
    }
    expect(decodeGb18030(fourByteSequence(189000))).toBe('\u{10000}')
  })

  it('ASCII 与欧元符原样通过', () => {
    expect(decodeGb18030(new Uint8Array([0x68, 0x69]))).toBe('hi')
    expect(decodeGb18030(new Uint8Array([0x80]))).toBe('€')
  })

  it('解出常见汉字与全角标点', () => {
    const bytes = new Uint8Array([0xcc, 0xfb, 0xd7, 0xd3, 0xb2, 0xbb, 0xb4, 0xe6, 0xd4, 0xda])
    expect(decodeGb18030(bytes)).toBe('帖子不存在')
    expect(decodeGb18030(new Uint8Array([0xd5, 0xd2, 0xb2, 0xbb, 0xb5, 0xbd, 0xd3, 0xc3, 0xbb, 0xa7]))).toBe(
      '找不到用户',
    )
  })

  it('非法序列的替换字符与 Node 逐个一致', () => {
    const cases: number[][] = [
      [0xcc], // 截断的前导字节
      [0xcc, 0x20], // 前导字节 + ASCII 尾字节：ASCII 退回流里
      [0xcc, 0x30], // 会被当成四字节序列的开头，然后失败
      [0xcc, 0x30, 0x20],
      [0xcc, 0x30, 0x81],
      [0xcc, 0x30, 0x81, 0x41],
      [0xff],
      [0xff, 0x41],
      [0x81, 0x30, 0x81, 0x30],
      [0xfe, 0x39, 0xfe, 0x39], // pointer 越界
      [0x68, 0xcc, 0xf9, 0x69],
    ]
    for (const bytes of cases) {
      const input = new Uint8Array(bytes)
      expect(decodeGb18030(input), bytes.map((b) => b.toString(16)).join(' ')).toBe(
        reference.decode(input),
      )
    }
  })

  it('随机字节流与 Node 对拍（伪随机 2000 条）', () => {
    // 固定种子的 xorshift，保证失败可复现
    let seed = 0x2f6e2b1
    const next = () => {
      seed ^= seed << 13
      seed ^= seed >>> 17
      seed ^= seed << 5
      return (seed >>> 0) % 256
    }
    const mismatches: string[] = []
    for (let n = 0; n < 2000; n++) {
      const length = 1 + (next() % 16)
      const bytes = new Uint8Array(length)
      for (let i = 0; i < length; i++) bytes[i] = next()
      const actual = decodeGb18030(bytes)
      const expected = reference.decode(bytes)
      if (actual !== expected) {
        mismatches.push(`${[...bytes].map((b) => b.toString(16)).join(' ')}`)
      }
    }
    expect(mismatches).toEqual([])
  })

  it('空输入得到空串', () => {
    expect(decodeGb18030(new Uint8Array([]))).toBe('')
  })
})

describe('gbkEncodeURIComponent', () => {
  it('ASCII 按 URL 规则转义', () => {
    expect(gbkEncodeURIComponent('abc-1_2.3')).toBe('abc-1_2.3')
    expect(gbkEncodeURIComponent('a b&c=d')).toBe('a%20b%26c%3Dd')
  })

  it('汉字编成 GBK 双字节', () => {
    expect(gbkEncodeURIComponent('原神')).toBe('%D4%AD%C9%F1')
  })

  it('编码结果解回原文（全表往返）', () => {
    const mismatches: string[] = []
    for (let pointer = 0; pointer < GB18030_TWO_BYTE_INDEX.length; pointer++) {
      const char = GB18030_TWO_BYTE_INDEX[pointer]!
      const encoded = gbkEncodeURIComponent(char)
      const bytes = new Uint8Array(
        encoded
          .slice(1)
          .split('%')
          .map((hex) => Number.parseInt(hex, 16)),
      )
      if (decodeGb18030(bytes) !== char) mismatches.push(`pointer ${pointer}: ${char}`)
    }
    expect(mismatches).toEqual([])
  })

  it('GBK 表外的字符退化成十进制实体', () => {
    // emoji 不在 GBK 里，浏览器提交 GBK 表单时写成 &#128514;
    expect(gbkEncodeURIComponent('😂')).toBe('%26%23128514%3B')
  })
})
