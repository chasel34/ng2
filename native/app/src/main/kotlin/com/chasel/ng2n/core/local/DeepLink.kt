package com.chasel.ng2n.core.local

import com.chasel.ng2n.core.net.encoding.decodeUriComponentOrNull
import com.chasel.ng2n.core.net.encoding.encodeUriComponent

/**
 * 深链解析:把外部点进来的链接、以及抽屉「由 URL 读取」里粘的那一行,归成 app 内的
 * 一个跳转目标。直译 `src/core/local/deep-link.ts`。
 *
 * 认这几种写法:
 * ```
 * https://bbs.nga.cn/read.php?tid=123&page=2#pid456Anchor
 * ng2://read.php?tid=123          release 自定义 scheme(spec §2)
 * ng2-dev://read.php?tid=123      development 自定义 scheme
 * bbs.nga.cn/read.php?tid=123     手粘时常见的省略 scheme
 * /thread.php?fid=650             同上,只剩路径
 * ```
 *
 * **不借 `java.net.URI`/`Uri`**:core 不碰平台 API,而且 `URI` 对 `随便一句话`
 * 这种非法输入是抛异常而不是给个结论——从 scheme 到 query 全部手切,与 RN 版逐字对齐。
 */

/** release 自定义 scheme;development 另用 ng2-dev,避免并装时互相抢链接。 */
const val APP_SCHEME = "ng2"
const val APP_DEV_SCHEME = "ng2-dev"

private val APP_SCHEMES = listOf(APP_SCHEME, APP_DEV_SCHEME)

/**
 * 能接管的域名 = 官方域名清单(API 文档 §0.1),去掉协议头。
 *
 * TS 侧是从 `core/net/constants.ts` 的 `NGA_HOSTS` 派生的;Kotlin 侧那份常量归票 06/07,
 * 还没落盘——先在这里放一份,等 `core/net` 的常量文件进来后改成引用它。
 */
private val NGA_HOSTNAMES = listOf(
  "bbs.nga.cn",
  "ngabbs.com",
  "bbs.ngacn.cc",
  "nga.178.com",
  "nga.donews.com",
)

/** 深链只接管这两个端点(spec §2)。 */
private val ENDPOINTS = mapOf(
  "read.php" to DeepLinkTarget.TOPIC,
  "thread.php" to DeepLinkTarget.BOARD,
)

private enum class DeepLinkTarget { TOPIC, BOARD }

/** 版块列表页的两种 id:普通版块的 fid,与合集的 stid。 */
enum class NgaBoardKind(val wire: String) { BOARD("board"), COLLECTION("collection") }

sealed interface NgaLink {
  /** 主题详情:`read.php` 那一族参数(API 文档 §3)。 */
  data class Topic(
    val tid: Long,
    val page: Long? = null,
    /** 定位到某一楼;query 的 `pid` 或网页锚点 `#pid<pid>Anchor` */
    val pid: Long? = null,
    /** fav 码:隐藏/过期帖的钥匙,跳过去要一路带着 */
    val fav: String? = null,
  ) : NgaLink

  /** 主题列表:`thread.php` 的 fid / stid(API 文档 §2)。 */
  data class Board(
    /** 合集是 stid、普通版块是 fid,两者共用列表页的 `id` */
    val id: Long,
    val boardKind: NgaBoardKind,
  ) : NgaLink
}

/** 解不出来的几种原因——UI 要据此说清楚是哪儿不对。 */
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

/** JS 的 `Number.MAX_SAFE_INTEGER`。 */
private const val MAX_SAFE_INTEGER = 9007199254740991L

/**
 * 解析一条链接。
 *
 * 失败一律是 [NgaLinkResult.Failed],**不抛异常**——调用方里有一个是系统深链回调,
 * 那儿抛错会直接崩掉冷启动。
 */
fun parseNgaLink(input: String): NgaLinkResult {
  val trimmed = input.jsTrim()
  if (trimmed.isEmpty()) return NgaLinkResult.Failed(NgaLinkFailure.EMPTY)

  val location = splitLocation(trimmed)
  val web = location.scheme == "http" || location.scheme == "https"
  if (location.scheme != null && !web && location.scheme !in APP_SCHEMES) {
    return NgaLinkResult.Failed(NgaLinkFailure.UNSUPPORTED_SCHEME)
  }

  val host = normalizeHost(location.host)
  // `ng2://read.php?tid=1` 与手粘的 `read.php?tid=1` 在 host 的位置上放的是端点而不是域名;
  // 除此之外,只要 host 位置有东西,就必须是官方域名——否则等于替别人的站接管链接
  val hostIsDomain = web || (host.isNotEmpty() && ENDPOINTS[host] == null)
  if (hostIsDomain && host !in NGA_HOSTNAMES) {
    return NgaLinkResult.Failed(NgaLinkFailure.FOREIGN_HOST)
  }

  val endpoint = lastSegment(if (hostIsDomain) location.path else "$host/${location.path}")
  val target = ENDPOINTS[endpoint] ?: return NgaLinkResult.Failed(NgaLinkFailure.UNSUPPORTED_PATH)

  val params = parseQuery(location.query)
  return if (target == DeepLinkTarget.TOPIC) topicLink(params, location.fragment) else boardLink(params)
}

/**
 * 目标 → app 内路由。给系统深链入口用(那儿只能返回字符串),「由 URL 读取」也走同一条,
 * 免得两处各写一份参数映射迟早走偏。
 */
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
  // `read.php?pid=xxx` 这种只给 pid 的引用链接也存在,但换算成 tid 要再打一次接口,
  // 不在深链这一层做——直接说清楚缺什么
  val tid = positiveInt(params["tid"]) ?: return NgaLinkResult.Failed(NgaLinkFailure.MISSING_ID)
  val page = positiveInt(params["page"])
  // 网页版定位某楼靠的是锚点 `#pid<pid>Anchor`,query 里没写 pid 时按它算
  val pid = positiveInt(params["pid"]) ?: positiveInt(ANCHOR_PID.find(fragment)?.groupValues?.get(1))
  val fav = favCode(params["fav"])
  return NgaLinkResult.Ok(NgaLink.Topic(tid = tid, page = page, pid = pid, fav = fav))
}

private fun boardLink(params: Map<String, String>): NgaLinkResult {
  // stid 与 fid 互斥且 stid 优先(合集)
  val stid = boardId(params["stid"])
  if (stid != null) return NgaLinkResult.Ok(NgaLink.Board(stid, NgaBoardKind.COLLECTION))
  val fid = boardId(params["fid"])
  if (fid != null) return NgaLinkResult.Ok(NgaLink.Board(fid, NgaBoardKind.BOARD))
  return NgaLinkResult.Failed(NgaLinkFailure.MISSING_ID)
}

private data class Location(
  /** 小写;没写 scheme 时是 `null` */
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
  // 从网页正文复制来的链接常把 `&` 带成 HTML 实体
  for (pair in AMP_ENTITY.replace(query, "&").split("&")) {
    if (pair.isEmpty()) continue
    val eq = pair.indexOf('=')
    val key = (if (eq == -1) pair else pair.substring(0, eq)).lowercase()
    // 同名参数以第一个为准
    if (params.containsKey(key)) continue
    params[key] = decodeValue(if (eq == -1) "" else pair.substring(eq + 1))
  }
  return params
}

private fun decodeValue(value: String): String {
  val plusDecoded = value.replace('+', ' ')
  // 半截的 `%` 转义解不开,原样留着让下面的格式校验去否
  return decodeUriComponentOrNull(plusDecoded) ?: value
}

private fun positiveInt(value: String?): Long? {
  if (value == null || !DIGITS.matches(value)) return null
  val parsed = value.toLongOrNull() ?: return null
  return if (parsed <= MAX_SAFE_INTEGER && parsed > 0L) parsed else null
}

/** fid 可以是负数(如 -7),stid 不会——两者共用这一套校验,只挡 0 与非整数。 */
private fun boardId(value: String?): Long? {
  if (value == null || !SIGNED_DIGITS.matches(value)) return null
  val parsed = value.toLongOrNull() ?: return null
  return if (parsed in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER && parsed != 0L) parsed else null
}

/** fav 码是十六进制串(API 文档 §2),别的形状一律当没带。 */
private fun favCode(value: String?): String? =
  if (value != null && HEX_STRING.matches(value)) value else null
