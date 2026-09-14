package com.chasel.ng2n.core.ai

import java.net.URI

@kotlinx.serialization.Serializable
data class AiWebSource(val url: String, val title: String, val bodyRead: Boolean = false,
  val truncated: Boolean = false)

@kotlinx.serialization.Serializable
data class WebSearchHit(val title: String, val url: String, val snippet: String)

@kotlinx.serialization.Serializable
data class WebSearchPage(val status: String, val hits: List<WebSearchHit> = emptyList())

const val WEB_SEARCH_OK = "ok"
const val WEB_SEARCH_CHALLENGE = "challenge"

const val WEB_PAGE_PAGE = 16000
const val WEB_PAGE_LIMIT = 60000

private val RESULT_LINK = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)
private val SNIPPET_CELL = Regex("<td[^>]*class\\s*=\\s*[\"']?result-snippet[\"']?[^>]*>(.*?)</td>",
  setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ATTRIBUTE = Regex("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
// 实测的验证页只带 anomaly-modal 与 /assets/anomaly 资源路径，没有 anomaly.html 之类的字样。
private val CHALLENGE_MARKERS = listOf("anomaly-modal", "/assets/anomaly", "challenge-submit", "anomaly.html",
  "anomaly.js", "challenge-form", "unusual activity", "bots use duckduckgo", "g-recaptcha", "cf-challenge", "captcha")
private val EMPTY_MARKERS = listOf("no results", "没有找到", "not many great matches")

fun parseDuckDuckGoLite(html: String): WebSearchPage {
  val hits = mutableListOf<WebSearchHit>()
  val snippets = SNIPPET_CELL.findAll(html).map { it.range.first to htmlToText(it.groupValues[1]) }.toList()
  var used = 0
  RESULT_LINK.findAll(html).forEach { anchor ->
    val attributes = ATTRIBUTE.findAll(anchor.value)
      .associate { it.groupValues[1].lowercase() to (it.groupValues[3] + it.groupValues[4] + it.groupValues[5]) }
    if ("result-link" !in attributes["class"].orEmpty().split(' ')) return@forEach
    val end = html.indexOf("</a>", anchor.range.last, ignoreCase = true)
    if (end < 0) return@forEach
    val title = htmlToText(html.substring(anchor.range.last + 1, end))
    val url = resolveLiteHref(attributes["href"].orEmpty()) ?: return@forEach
    while (used < snippets.size && snippets[used].first < end) used++
    val snippet = snippets.getOrNull(used)?.takeIf { it.first > end }
    if (snippet != null) used++
    hits += WebSearchHit(title.ifBlank { url }, url, snippet?.second.orEmpty())
  }
  if (hits.isNotEmpty()) return WebSearchPage(WEB_SEARCH_OK, hits)
  val lowercase = html.lowercase()
  if (CHALLENGE_MARKERS.any { it in lowercase }) return WebSearchPage(WEB_SEARCH_CHALLENGE)
  if (EMPTY_MARKERS.any { it in lowercase }) return WebSearchPage(WEB_SEARCH_OK)
  // 既没有结果行也没有已知标记的页面结构未知，按验证页处理，避免当成零结果。
  return WebSearchPage(WEB_SEARCH_CHALLENGE)
}

private fun resolveLiteHref(raw: String): String? {
  val href = htmlToText(raw).trim()
  if (href.isEmpty()) return null
  val absolute = if (href.startsWith("//")) "https:$href" else href
  val redirect = Regex("[?&]uddg=([^&]+)").find(absolute)?.groupValues?.get(1)
  val target = if (redirect == null) absolute else percentDecode(redirect)
  return target.takeIf { checkExternalUrl(it) == null }
}

fun webPageTitle(html: String): String? =
  Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    .find(html)?.groupValues?.get(1)?.let(::htmlToText)?.takeIf { it.isNotBlank() }

data class WebPageText(val text: String, val truncated: Boolean)

fun extractWebPage(html: String, limit: Int = WEB_PAGE_LIMIT): WebPageText {
  val stripped = html
    .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
    .replace(Regex("<(script|style|noscript|svg|head)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
    .replace(Regex("</?(br|p|div|li|tr|h[1-6]|section|article|header|footer|blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
  val text = htmlToText(stripped).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
  return WebPageText(text.take(limit), text.length > limit)
}

fun htmlToText(fragment: String): String =
  decodeEntities(fragment.replace(Regex("<[^>]*>"), " ")).replace(Regex("[ \\t\\u00a0]+"), " ").trim()

private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
  "nbsp" to "\u00a0", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘",
  "ldquo" to "“", "rdquo" to "”", "middot" to "·", "times" to "×", "raquo" to "»", "laquo" to "«")

private fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
  val body = match.groupValues[1]
  val code = when {
    body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)
    body.startsWith("#") -> body.drop(1).toIntOrNull()
    else -> null
  }
  when {
    code != null && code in 1..0x10FFFF -> String(Character.toChars(code))
    code != null -> match.value
    else -> NAMED[body.lowercase()] ?: match.value
  }
}

private fun percentDecode(value: String): String {
  val bytes = java.io.ByteArrayOutputStream()
  var i = 0
  while (i < value.length) {
    val char = value[i]
    when {
      char == '%' && i + 2 < value.length -> {
        val code = value.substring(i + 1, i + 3).toIntOrNull(16)
        if (code == null) { bytes.write(char.code); i++ } else { bytes.write(code); i += 3 }
      }
      char == '+' -> { bytes.write(' '.code); i++ }
      else -> { bytes.write(char.toString().toByteArray(Charsets.UTF_8)); i++ }
    }
  }
  return bytes.toString(Charsets.UTF_8.name())
}

fun checkExternalUrl(raw: String): String? {
  val text = raw.trim()
  if (text.isEmpty()) return "缺少网址"
  val uri = try { URI(text) } catch (_: Exception) { return "网址无法解析" }
  val scheme = uri.scheme?.lowercase()
  if (scheme != "http" && scheme != "https") return "只允许 http 或 https 网址"
  if (uri.userInfo != null || "@" in text.substringAfter("//").substringBefore('/')) return "网址不得携带账号信息"
  val host = (uri.host ?: return "网址缺少主机名").lowercase().removeSurrounding("[", "]")
  if (isPrivateHost(host)) return "拒绝读取私网地址或本机地址"
  return null
}

fun isPrivateHost(host: String): Boolean {
  val name = host.trim('.').lowercase()
  if (name.isEmpty()) return true
  if (name == "localhost" || LOCAL_SUFFIXES.any { name.endsWith(it) }) return true
  parseIpv4(name)?.let { return isPrivateAddress(it) }
  if (':' in name) return !isGlobalIpv6(name)
  return false
}

private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".localdomain", ".internal", ".home.arpa")

private fun parseIpv4(host: String): ByteArray? {
  val parts = host.split('.')
  if (parts.size != 4) return null
  val octets = parts.map { it.toIntOrNull() ?: return null }
  if (octets.any { it !in 0..255 }) return null
  return ByteArray(4) { octets[it].toByte() }
}

private fun isGlobalIpv6(host: String): Boolean {
  val mapped = host.substringAfterLast(':')
  parseIpv4(mapped)?.let { return !isPrivateAddress(it) }
  val head = host.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
  // 只放行全球单播 2000::/3；其余（回环、链路本地、唯一本地、保留段）一律拒绝。
  return head in 0x2000..0x3fff
}

fun isPrivateAddress(address: ByteArray): Boolean {
  if (address.size == 16) {
    if (address.take(10).all { it.toInt() == 0 } && address[10] == (-1).toByte() && address[11] == (-1).toByte())
      return isPrivateAddress(address.copyOfRange(12, 16))
    val head = ((address[0].toInt() and 0xff) shl 8) or (address[1].toInt() and 0xff)
    return head !in 0x2000..0x3fff
  }
  if (address.size != 4) return true
  val a = address[0].toInt() and 0xff
  val b = address[1].toInt() and 0xff
  return when {
    a == 0 || a == 10 || a == 127 || a >= 224 -> true
    a == 169 && b == 254 -> true
    a == 172 && b in 16..31 -> true
    a == 192 && b == 168 -> true
    a == 192 && b == 0 -> true
    a == 100 && b in 64..127 -> true
    a == 198 && b in 18..19 -> true
    else -> false
  }
}

fun webReadState(web: AiWebSource): String = when {
  !web.bodyRead -> "仅搜索摘要，未读正文，不能作为已核实证据"
  web.truncated -> "正文超过读取上限，只读到前一部分，不能声称已读全文"
  else -> "已读正文"
}

fun webReadLabel(web: AiWebSource): String = when {
  !web.bodyRead -> "仅搜索摘要"
  web.truncated -> "正文未读完"
  else -> "已读正文"
}

fun webDomain(url: String): String =
  (runCatching { URI(url).host }.getOrNull() ?: url).removePrefix("www.").lowercase()
