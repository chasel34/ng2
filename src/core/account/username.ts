import { REPLACEMENT_CHAR, decodeGb18030 } from '../net/encoding/gb18030'

/**
 * `ngaPassportUrlencodedUname` 的解码：**GBK 字符集 URLDecode 两次**（API 文档 §0.2）。
 *
 * 服务端是 URLEncode(URLEncode(name, GBK), GBK)——第一层解出来仍是 `%XX` 的 ASCII 文本，
 * 第二层解出来才是 GBK 字节，最后过 GB18030 解码器。不能用 decodeURIComponent：
 * 它按 UTF-8 解字节，GBK 序列会直接抛 URIError。
 */

/**
 * 单层 URLDecode 到字节。兼容 Java URLEncoder 的两个习惯：
 * `+` 表示空格、十六进制大小写不定。畸形输入（孤立的 %、非单字节字符）返回 null。
 */
function percentDecodeToBytes(input: string): Uint8Array | null {
  const bytes: number[] = []
  for (let i = 0; i < input.length; i += 1) {
    const char = input[i]!
    if (char === '%') {
      const hex = input.slice(i + 1, i + 3)
      if (!/^[0-9a-fA-F]{2}$/.test(hex)) return null
      bytes.push(Number.parseInt(hex, 16))
      i += 2
      continue
    }
    if (char === '+') {
      bytes.push(0x20)
      continue
    }
    const code = char.charCodeAt(0)
    // URL 编码的产物只可能是单字节字符，出现更宽的说明整串不是编码结果
    if (code > 0xff) return null
    bytes.push(code)
  }
  return Uint8Array.from(bytes)
}

/** 解码用户名；解不动（畸形/解出替换字符/空串）返回 null，调用方回落到 UID 展示。 */
export function decodeLoginUsername(raw: string): string | null {
  const once = percentDecodeToBytes(raw)
  if (once === null) return null
  // 第一层的产物按单字节直转回文本（全 ASCII），再解第二层
  let asText = ''
  for (const byte of once) asText += String.fromCharCode(byte)
  const twice = percentDecodeToBytes(asText)
  if (twice === null) return null
  const name = decodeGb18030(twice).trim()
  if (name === '' || name.includes(REPLACEMENT_CHAR)) return null
  return name
}
