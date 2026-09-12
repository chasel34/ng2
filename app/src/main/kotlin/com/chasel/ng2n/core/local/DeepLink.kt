package com.chasel.ng2n.core.local

import com.chasel.ng2n.core.net.NGA_HOSTS
import com.chasel.ng2n.core.net.encoding.decodeUriComponentOrNull
import com.chasel.ng2n.core.net.encoding.encodeUriComponent

const val APP_SCHEME = "ng2n"
const val RN_SCHEME = "ng2"
const val RN_DEV_SCHEME = "ng2-dev"

private val APP_SCHEMES = listOf(APP_SCHEME, RN_SCHEME, RN_DEV_SCHEME)

private val NGA_HOSTNAMES = NGA_HOSTS.map { it.substringAfter("://") }

private val ENDPOINTS = mapOf(
  "read.php" to DeepLinkTarget.TOPIC,
  "thread.php" to DeepLinkTarget.BOARD,
)

private enum class DeepLinkTarget { TOPIC, BOARD }

enum class NgaBoardKind(val wire: String) { BOARD("board"), COLLECTION("collection") }

sealed interface NgaLink {
  data class Topic(
    val tid: Long,
    val page: Long? = null,
    val pid: Long? = null,
    val fav: String? = null,
  ) : NgaLink

  data class Board(
    val id: Long,
    val boardKind: NgaBoardKind,
  ) : NgaLink
}

enum class NgaLinkFailure(val wire: String, val message: String) {
  EMPTY("empty", "先粘一条 NGA 链接进来"),
  UNSUPPORTED_SCHEME("unsupported-scheme", "这不像一条链接"),
  FOREIGN_HOST("foreign-host", "只认 NGA 官方域名的链接"),
  UNSUPPORTED_PATH("unsupported-path", "只支持主题(read.php)与版块(thread.php)链接"),
  MISSING_ID("missing-id", "链接里没有 tid / fid,不知道要打开什么"),
}

sealed interface NgaLinkResult {
  data class Ok(val link: NgaLink) : NgaLinkResult
  data class Failed(val reason: NgaLinkFailure) : NgaLinkResult
}

private val SCHEME_PATTERN = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$")
private val PORT_SUFFIX = Regex(":\\d*$")
private val ANCHOR_PID = Regex("^pid(\\d+)", RegexOption.IGNORE_CASE)
private val DIGITS = Regex("^\\d+$")
private val SIGNED_DIGITS = Regex("^-?\\d+$")
private val HEX_STRING = Regex("^[0-9a-fA-F]+$")
private val AMP_ENTITY = Regex("&amp;", RegexOption.IGNORE_CASE)

private const val MAX_SAFE_INTEGER = 9007199254740991L

fun parseNgaLink(input: String): NgaLinkResult {
  val trimmed = input.jsTrim()
  if (trimmed.isEmpty()) return NgaLinkResult.Failed(NgaLinkFailure.EMPTY)

  val location = splitLocation(trimmed)
  val web = location.scheme == "http" || location.scheme == "https"
  if (location.scheme != null && !web && location.scheme !in APP_SCHEMES) {
    return NgaLinkResult.Failed(NgaLinkFailure.UNSUPPORTED_SCHEME)
  }

  val host = normalizeHost(location.host)
  val hostIsDomain = web || (host.isNotEmpty() && ENDPOINTS[host] == null)
  if (hostIsDomain && host !in NGA_HOSTNAMES) {
    return NgaLinkResult.Failed(NgaLinkFailure.FOREIGN_HOST)
  }

  val endpoint = lastSegment(if (hostIsDomain) location.path else "$host/${location.path}")
  val target = ENDPOINTS[endpoint] ?: return NgaLinkResult.Failed(NgaLinkFailure.UNSUPPORTED_PATH)

  val params = parseQuery(location.query)
  return if (target == DeepLinkTarget.TOPIC) topicLink(params, location.fragment) else boardLink(params)
}

fun ngaLinkPath(link: NgaLink): String = when (link) {
  is NgaLink.Board -> "/board/${link.id}?kind=${link.boardKind.wire}"
  is NgaLink.Topic -> {
    val query = buildList {
      link.page?.let { add("page=$it") }
      link.pid?.let { add("pid=$it") }
      link.fav?.let { add("fav=${encodeUriComponent(it)}") }
    }
    "/topic/${link.tid}" + if (query.isEmpty()) "" else "?${query.joinToString("&")}"
  }
}

private fun topicLink(params: Map<String, String>, fragment: String): NgaLinkResult {
  val tid = positiveInt(params["tid"]) ?: return NgaLinkResult.Failed(NgaLinkFailure.MISSING_ID)
  val page = positiveInt(params["page"])
  val pid = positiveInt(params["pid"]) ?: positiveInt(ANCHOR_PID.find(fragment)?.groupValues?.get(1))
  val fav = favCode(params["fav"])
  return NgaLinkResult.Ok(NgaLink.Topic(tid = tid, page = page, pid = pid, fav = fav))
}

private fun boardLink(params: Map<String, String>): NgaLinkResult {
  val stid = boardId(params["stid"])
  if (stid != null) return NgaLinkResult.Ok(NgaLink.Board(stid, NgaBoardKind.COLLECTION))
  val fid = boardId(params["fid"])
  if (fid != null) return NgaLinkResult.Ok(NgaLink.Board(fid, NgaBoardKind.BOARD))
  return NgaLinkResult.Failed(NgaLinkFailure.MISSING_ID)
}

private data class Location(
  val scheme: String?,
  val host: String,
  val path: String,
  val query: String,
  val fragment: String,
)

private fun splitLocation(input: String): Location {
  val matched = SCHEME_PATTERN.find(input)
  val scheme = matched?.groupValues?.get(1)?.lowercase()
  var rest = matched?.groupValues?.get(2) ?: input

  val hash = rest.indexOf('#')
  val fragment = if (hash == -1) "" else rest.substring(hash + 1)
  if (hash != -1) rest = rest.substring(0, hash)

  val mark = rest.indexOf('?')
  val query = if (mark == -1) "" else rest.substring(mark + 1)
  if (mark != -1) rest = rest.substring(0, mark)

  if (rest.startsWith("/")) return Location(scheme, "", rest, query, fragment)
  val slash = rest.indexOf('/')
  if (slash == -1) return Location(scheme, rest, "", query, fragment)
  return Location(scheme, rest.substring(0, slash), rest.substring(slash), query, fragment)
}

private fun normalizeHost(raw: String): String {
  val withoutUserInfo = raw.substring(raw.lastIndexOf('@') + 1)
  return withoutUserInfo
    .replace(PORT_SUFFIX, "")
    .lowercase()
    .removeSuffix(".")
    .removePrefix("www.")
}

private fun lastSegment(path: String): String =
  path.split("/").filter { it.isNotEmpty() }.lastOrNull().orEmpty().lowercase()

private fun parseQuery(query: String): Map<String, String> {
  val params = LinkedHashMap<String, String>()
  for (pair in AMP_ENTITY.replace(query, "&").split("&")) {
    if (pair.isEmpty()) continue
    val eq = pair.indexOf('=')
    val key = (if (eq == -1) pair else pair.substring(0, eq)).lowercase()
    if (params.containsKey(key)) continue
    params[key] = decodeValue(if (eq == -1) "" else pair.substring(eq + 1))
  }
  return params
}

private fun decodeValue(value: String): String {
  val plusDecoded = value.replace('+', ' ')
  return decodeUriComponentOrNull(plusDecoded) ?: value
}

private fun positiveInt(value: String?): Long? {
  if (value == null || !DIGITS.matches(value)) return null
  val parsed = value.toLongOrNull() ?: return null
  return if (parsed <= MAX_SAFE_INTEGER && parsed > 0L) parsed else null
}

private fun boardId(value: String?): Long? {
  if (value == null || !SIGNED_DIGITS.matches(value)) return null
  val parsed = value.toLongOrNull() ?: return null
  return if (parsed in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER && parsed != 0L) parsed else null
}

private fun favCode(value: String?): String? =
  if (value != null && HEX_STRING.matches(value)) value else null
