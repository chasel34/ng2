import { REPLACEMENT_CHAR, decodeGb18030 } from './gb18030'

const GBK_CHARSETS = new Set(['gbk', 'gb18030', 'gb2312', 'x-gbk', 'csgb2312', 'gb_2312-80'])
const UTF8_CHARSETS = new Set(['utf-8', 'utf8', 'unicode-1-1-utf-8'])

/** 从 `Content-Type` 里抠 charset，小写返回；没有则 null。 */
export function parseCharset(contentType: string | null | undefined): string | null {
  if (!contentType) return null
  const match = /charset\s*=\s*"?([\w-]+)"?/i.exec(contentType)
  return match ? match[1]!.toLowerCase() : null
}

function decodeUtf8(bytes: Uint8Array): string {
  // Hermes 只支持 utf-8 这一种 TextDecoder，正好够用
  return new TextDecoder('utf-8').decode(bytes)
}

function stripBom(text: string): string {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
}

function countReplacements(text: string): number {
  let count = 0
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) === 0xfffd) count += 1
  }
  return count
}

/**
 * 按 API 文档 §0.5 解码响应体：优先信 `Content-Type` 声明的 charset，
 * 未声明时回落 GB18030（MNGA 的做法）。
 *
 * 与 MNGA 不同的是未声明时先试 UTF-8：本项目跟 MNGA 一样带 `__inchst=UTF8`，
 * 服务端多数时候确实返回 UTF-8 却不声明 charset，硬按 GB18030 解会整篇乱码。
 * 判据是替换字符谁少用谁——GBK 中文按 UTF-8 解几乎必然出现大量 U+FFFD，反之亦然。
 */
export function decodeResponseBody(
  bytes: Uint8Array,
  contentType?: string | null,
): string {
  const charset = parseCharset(contentType)
  if (charset && GBK_CHARSETS.has(charset)) return stripBom(decodeGb18030(bytes))
  if (charset && UTF8_CHARSETS.has(charset)) return stripBom(decodeUtf8(bytes))

  const utf8 = decodeUtf8(bytes)
  if (!utf8.includes(REPLACEMENT_CHAR)) return stripBom(utf8)

  const gbk = decodeGb18030(bytes)
  return stripBom(countReplacements(gbk) < countReplacements(utf8) ? gbk : utf8)
}
