package com.chasel.ng2n.core.net.encoding

private val GBK_CHARSETS = setOf("gbk", "gb18030", "gb2312", "x-gbk", "csgb2312", "gb_2312-80")
private val UTF8_CHARSETS = setOf("utf-8", "utf8", "unicode-1-1-utf-8")

private val CHARSET_PATTERN = Regex("charset\\s*=\\s*\"?([\\w-]+)\"?", RegexOption.IGNORE_CASE)

fun parseCharset(contentType: String?): String? {
  if (contentType.isNullOrEmpty()) return null
  val match = CHARSET_PATTERN.find(contentType) ?: return null
  return match.groupValues[1].lowercase()
}

private fun decodeUtf8(bytes: ByteArray): String = stripBom(String(bytes, Charsets.UTF_8))

private fun stripBom(text: String): String = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

private fun countReplacements(text: String): Int = text.count { it == REPLACEMENT_CHAR }

fun decodeResponseBody(bytes: ByteArray, contentType: String? = null): String {
  val charset = parseCharset(contentType)
  if (charset != null && charset in GBK_CHARSETS) return stripBom(decodeGb18030(bytes))
  if (charset != null && charset in UTF8_CHARSETS) return stripBom(decodeUtf8(bytes))

  val utf8 = decodeUtf8(bytes)
  if (!utf8.contains(REPLACEMENT_CHAR)) return stripBom(utf8)

  val gbk = decodeGb18030(bytes)
  return stripBom(if (countReplacements(gbk) < countReplacements(utf8)) gbk else utf8)
}
