package com.chasel.ng2n.core.net.encoding

private val GBK_CHARSETS = setOf("gbk", "gb18030", "gb2312", "x-gbk", "csgb2312", "gb_2312-80")
private val UTF8_CHARSETS = setOf("utf-8", "utf8", "unicode-1-1-utf-8")

private val CHARSET_PATTERN = Regex("charset\\s*=\\s*\"?([\\w-]+)\"?", RegexOption.IGNORE_CASE)

/** 从 `Content-Type` 里抠 charset,小写返回;没有则 null。 */
fun parseCharset(contentType: String?): String? {
  if (contentType.isNullOrEmpty()) return null
  val match = CHARSET_PATTERN.find(contentType) ?: return null
  return match.groupValues[1].lowercase()
}

/**
 * WHATWG 的 `TextDecoder('utf-8')` 会**自己吃掉一个前导 BOM**(`ignoreBOM` 默认 false),
 * 外层 [stripBom] 再吃一个 —— 所以 RN 版的 UTF-8 路径最多剥两个 BOM,GBK 路径只剥一个。
 * JVM 的 `String(bytes, UTF_8)` 不剥,这里补上,免得「两个 BOM」这种脏样本两边不一致。
 */
private fun decodeUtf8(bytes: ByteArray): String = stripBom(String(bytes, Charsets.UTF_8))

private fun stripBom(text: String): String = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

private fun countReplacements(text: String): Int = text.count { it == REPLACEMENT_CHAR }

/**
 * 按 API 文档 §0.5 解码响应体:优先信 `Content-Type` 声明的 charset,
 * 未声明时回落 GB18030(MNGA 的做法)。
 *
 * 与 MNGA 不同的是未声明时先试 UTF-8:本项目跟 MNGA 一样带 `__inchst=UTF8`,
 * 服务端多数时候确实返回 UTF-8 却不声明 charset,硬按 GB18030 解会整篇乱码。
 * 判据是替换字符谁少用谁——GBK 中文按 UTF-8 解几乎必然出现大量 U+FFFD,反之亦然。
 *
 * 四条分支一条都不能少(移植风险 TOP5 #3,漏一处 = 「偶发乱码」级难查 bug):
 * 1. 声明了 GBK 家族 → GB18030;
 * 2. 声明了 UTF-8 家族 → UTF-8;
 * 3. **不认识的 charset 不硬用**,退回投票;
 * 4. 未声明 → 先 UTF-8,一个 U+FFFD 都没有就直接采纳;有才解一遍 GB18030 投票,
 *    替换字符少的胜出,**平手留 UTF-8**。
 * 最后一律剥 BOM。
 */
fun decodeResponseBody(bytes: ByteArray, contentType: String? = null): String {
  val charset = parseCharset(contentType)
  if (charset != null && charset in GBK_CHARSETS) return stripBom(decodeGb18030(bytes))
  if (charset != null && charset in UTF8_CHARSETS) return stripBom(decodeUtf8(bytes))

  val utf8 = decodeUtf8(bytes)
  if (!utf8.contains(REPLACEMENT_CHAR)) return stripBom(utf8)

  val gbk = decodeGb18030(bytes)
  return stripBom(if (countReplacements(gbk) < countReplacements(utf8)) gbk else utf8)
}
