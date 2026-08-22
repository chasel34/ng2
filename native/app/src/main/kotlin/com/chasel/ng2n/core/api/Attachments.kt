package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.encoding.decodeUriComponentOrNull
import java.time.Instant
import java.time.ZoneOffset

/**
 * 附件图片地址拼装(API 文档 §0.1、§3)。直译 `src/core/api/attachments.ts`。
 *
 * 三件事在这里合流:
 *
 * 1. **域名不写死**。`read.php` 每次都在 `__GLOBAL._ATTACH_BASE_VIEW` 里带当前的附件基址,
 *    NGA 换过好几次域名(`img.nga.178.com` / `ngacn.cc` / `img.nga.cn`),写死就等着失效。
 * 2. **相对路径的两种形态**。`[img]./mon_202608/07/x.jpg[/img]` 自带日期目录,
 *    而 `[noimg]./-7Qd36d-….jpg[/noimg]` 没有——后者要按发帖时间补 `mon_YYYYMM/DD/`
 *    才能取到图(实测缺前缀的地址是 404)。
 * 3. **老帖正文里写死的绝对地址要重挂**,见 [rehostLegacyAttachment]。
 */

/**
 * 响应里没有 `_ATTACH_BASE_VIEW` 时的兜底基址。
 *
 * 只在字段缺失(被封、Web 反解、旧缓存)时用得上:宁可拿一个可能过期的域名去试,
 * 也好过整楼图片全渲染不出来。正常路径一律用响应给的值。
 */
const val ATTACH_BASE_FALLBACK = "img.nga.cn/attachments"

/** 缩略图后缀(旧 Android 客户端 `ForumImageDecoder` 的同一张表)。 */
private val THUMBNAIL_SUFFIXES = listOf(".thumb_ss.jpg", ".thumb_s.jpg", ".thumb.jpg", ".medium.jpg")

/** 服务端给的缩略图后缀,展示时要剥掉换回原图。 */
const val THUMBNAIL_SUFFIX = ".thumb.jpg"

/**
 * NGA 的日期目录按论坛所在时区(UTC+8)分,**不能跟着设备时区走**。
 * 所以这里显式用 `ZoneOffset.ofHours(8)`,不用 `ZoneId.systemDefault()`。
 */
private val NGA_ZONE: ZoneOffset = ZoneOffset.ofHours(8)

/** 已经带日期目录的相对路径,例如 `mon_202608/07/x.jpg`。 */
private val DATED_PATH_PATTERN = Regex("""^mon_\d{6}/""")

private val SCHEME_PREFIX = Regex("^https?://")
private val TRAILING_SLASHES = Regex("/+$")

/**
 * 把 `__GLOBAL._ATTACH_BASE_VIEW` 归一成能直接往后拼路径的基址。
 *
 * 服务端给的是不带协议的 `img.nga.cn/attachments`。这里**保留整段路径**,
 * 而不是像旧 Android 客户端那样只取 `split("/")[0]` 再拼死的 `/attachments`——
 * 那等于把路径换个地方硬编码。
 *
 * `raw` 收 `Any?` 是照抄 TS 的 `unknown`:这个字段缺席、或偶尔是数字都出现过。
 */
fun normalizeAttachBase(raw: Any?): String {
  val value = if (raw is String) raw.trim() else ""
  val base = if (value.isEmpty()) ATTACH_BASE_FALLBACK else value
  // 明文流量默认禁,服务端给 http 也要升到 https
  val withoutScheme = SCHEME_PREFIX.replace(base, "")
  return "https://" + TRAILING_SLASHES.replace(withoutScheme, "")
}

/** 剥掉结尾的缩略图后缀,还原原图路径;没有后缀时原样返回。 */
fun stripThumbnailSuffix(src: String): String {
  for (suffix in THUMBNAIL_SUFFIXES) {
    if (src.endsWith(suffix)) return src.substring(0, src.length - suffix.length)
  }
  return src
}

/** `mon_YYYYMM/DD`(UTC+8),`[noimg]` 的相对路径缺的就是这一段。 */
private fun datedDirectory(postedAt: Long): String {
  val shifted = Instant.ofEpochSecond(postedAt).atOffset(NGA_ZONE)
  val month = shifted.monthValue.toString().padStart(2, '0')
  val day = shifted.dayOfMonth.toString().padStart(2, '0')
  return "mon_${shifted.year}$month/$day"
}

/**
 * 认「这是不是 NGA 自己的附件域名」。域名换过好几次,老域名的地址还留在老帖正文里。
 * 这张表只用来**认**,不用来拼——真正的目标基址仍然只从 `_ATTACH_BASE_VIEW` 来(ADR-0002)。
 */
private val NGA_ATTACH_HOST =
  Regex("""(?:^|\.)(?:nga\.cn|ngacn\.cc|nga\.178\.com)$""", RegexOption.IGNORE_CASE)

/** 绝对地址里的 `<host>/attachments/<路径>`;协议相对(`//`)的写法也收。 */
private val ABSOLUTE_ATTACHMENT =
  Regex("""^(?:https?:)?//([^/]+)/attachments/(.+)$""", RegexOption.IGNORE_CASE)

/**
 * 把老帖正文里写死的附件地址重挂到当前附件域名。
 *
 * 版头这类多年不动的帖子里,图片是绝对地址而不是 `./` 相对路径,例如
 * `[img]https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-….png[/img]`。
 * `img.nga.178.com` 已经停了(TLS 握手直接失败,2026-08-08 实测),而同一条
 * `mon_202006/03/…` 路径挂在响应给的 `img.nga.cn/attachments` 下仍然是 200——
 * 所以只要地址落在 NGA 的 `/attachments/` 目录里,就换成响应给的基址再拼。
 *
 * 站外图片(图床、外链)原样返回:那些地址跟附件域名没关系。
 */
fun rehostLegacyAttachment(src: String, base: String): String {
  val match = ABSOLUTE_ATTACHMENT.find(src) ?: return src
  // 端口不影响判定,取主机名部分即可
  val host = match.groupValues[1].substringBefore(':')
  if (!NGA_ATTACH_HOST.containsMatchIn(host)) return src
  return "$base/${match.groupValues[2]}"
}

/**
 * 换成缩略图地址(省流量那两档)。
 *
 * 只对挂在当前附件基址下的图动手:站外图床没有这套后缀约定,加上去就是 404。
 * 先剥再加,所以对已经是缩略图的地址是幂等的。
 */
fun thumbnailUrl(url: String, base: String): String {
  if (!url.startsWith("$base/")) return url
  return stripThumbnailSuffix(url) + THUMBNAIL_SUFFIX
}

/**
 * 落盘文件名里不敢要的字符(Android 文件系统 + MediaStore 的交集)。
 * `\s` 按 **JS 的空白表**展开(JDK 的 `\s` 只有 ASCII 六个,会漏掉 NBSP / 全角空格)。
 */
private val UNSAFE_FILENAME_CHARS = Regex(
  "[\\\\/:*?\"<>|%#\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+",
)

/** 常见图片扩展名 → MIME。落不进表的按 jpeg 兜底——NGA 附件绝大多数是 jpg。 */
private val IMAGE_MIME_TYPES = mapOf(
  "jpg" to "image/jpeg",
  "jpeg" to "image/jpeg",
  "png" to "image/png",
  "gif" to "image/gif",
  "webp" to "image/webp",
  "bmp" to "image/bmp",
  "avif" to "image/avif",
)

private val QUERY_OR_HASH = Regex("[?#]")

/**
 * 从图片地址取一个能直接落盘的文件名(保存到相册 / 分享的下载中转都用它)。
 *
 * 取路径最后一段、去掉查询串、剥掉缩略图后缀(存的是原图,名字不该带 `.thumb`)、
 * 替换文件系统不认的字符;没有认得出的图片扩展名时补 `.jpg`——
 * MediaStore 靠扩展名认类型,裸哈希名会存成「未知文件」进不了相册。
 */
fun imageFileName(url: String): String {
  val path = url.split(QUERY_OR_HASH).firstOrNull() ?: url
  val lastSegment = path.substring(path.lastIndexOf('/') + 1)
  val base = UNSAFE_FILENAME_CHARS.replace(
    stripThumbnailSuffix(decodeUriComponentSafe(lastSegment)),
    "_",
  )
  val named = if (base.isEmpty() || base == "_") "image-${hashOf(url)}" else base
  return if (extensionOf(named) == null) "$named.jpg" else named
}

/** 按文件名猜 MIME(系统分享面板要它来挑目标应用)。 */
fun imageMimeType(fileName: String): String =
  extensionOf(fileName)?.let { IMAGE_MIME_TYPES[it] } ?: "image/jpeg"

/** 认得出的图片扩展名(小写),认不出返回 `null`。 */
private fun extensionOf(fileName: String): String? {
  val dot = fileName.lastIndexOf('.')
  if (dot <= 0) return null
  val extension = fileName.substring(dot + 1).lowercase()
  return if (extension in IMAGE_MIME_TYPES) extension else null
}

/** 站外图床会出现 `%20` 这类转义;转不动的(裸 `%`)原样保留,不能抛。 */
private fun decodeUriComponentSafe(value: String): String =
  decodeUriComponentOrNull(value) ?: value

/** 文件名兜底用的短哈希(djb2)。只求稳定可辨,不求防碰撞。 */
private fun hashOf(value: String): String {
  var hash = 5381L // 始终维持在 ToUint32 的值域里,与 JS 的 `>>> 0` 同步
  for (char in value) {
    val shifted = (hash.toInt() shl 5).toLong() // JS 的 `hash << 5` 先 ToInt32
    hash = (shifted + hash + char.code.toLong()) and 0xffffffffL
  }
  return java.lang.Long.toString(hash, 36)
}

/**
 * 把 AST 里的资源引用拼成能直接喂给图片加载器的地址。
 *
 * 收的是 `AttachmentRef` 拆开的两个字段而不是那个类型本身:`AttachmentRef` 是
 * 票 09 的 `core/bbcode` 里的东西,这一层不该抢先定义它。
 *
 * @param src 引用里的原始地址
 * @param needsAttachBase 相对路径(`[img]./…[/img]` / `[noimg]`),要往附件基址上拼
 * @param base [normalizeAttachBase] 的产物
 * @param postedAt 所在楼层的发帖时间(秒级 unix),补日期目录用;没有就不猜
 */
fun attachmentUrl(src: String, needsAttachBase: Boolean, base: String, postedAt: Long? = null): String {
  if (!needsAttachBase) return rehostLegacyAttachment(src, base)

  var path = stripThumbnailSuffix(src).trimStart('/')
  if (postedAt != null && !DATED_PATH_PATTERN.containsMatchIn(path)) {
    path = "${datedDirectory(postedAt)}/$path"
  }
  return "$base/$path"
}
