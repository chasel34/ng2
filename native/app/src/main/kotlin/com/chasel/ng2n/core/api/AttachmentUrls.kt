package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.AttachmentRef

/**
 * 附件地址的几个纯函数(RN 侧原件 `src/core/api/attachments.ts`)。
 *
 * **归属**:这一族函数(含 `attachmentUrl` / `normalizeAttachBase` / `rehostLegacyAttachment`
 * 等本文件没有的那几个)整体归**票 10**,金样本 `attachments` domain 有 44 条要逐条对拍。
 * 票 12 只用到其中四个,又不能空着手等,所以这里:
 *
 * 1. 定义 [AttachmentUrls] 接口 —— 图片管线只依赖它,票 10 换实现时图片侧一行不动;
 * 2. 附一份 [DefaultAttachmentUrls] 最小实现,逐行照抄 TS 原件,并配了单测。
 *
 * **TODO(票 10)**:票 10 落地 `attachments` 全族 + 44 条金样本对拍后,把
 * [DefaultAttachmentUrls] 换成票 10 的实现(或让票 10 的实现直接实现本接口),
 * 本文件的实现体删除,接口保留。
 */
interface AttachmentUrls {
  /**
   * 把一条资源引用拼成能直接喂给图片组件的地址。
   *
   * 收散装的两个字段而不是只收 [AttachmentRef]:`[album]` 的内容是**一整串裸地址**
   * (票 09 把它原样留在 `value` 里),不是 AST 节点,而 [AttachmentRef] 是密封接口
   * ——包外造不出实例。两条路必须能走同一套拼装规则。
   */
  fun attachmentUrl(src: String, needsAttachBase: Boolean, options: AttachmentUrlOptions): String

  /** 把 AST 里的资源引用拼成能直接喂给图片组件的地址。 */
  fun attachmentUrl(ref: AttachmentRef, options: AttachmentUrlOptions): String =
    attachmentUrl(ref.src, ref.needsAttachBase, options)

  /** 把 `__GLOBAL._ATTACH_BASE_VIEW` 归一成能直接往后拼路径的基址。 */
  fun normalizeAttachBase(raw: String?): String

  /** 老帖正文里写死的绝对附件地址重挂到当前附件域名;站外图原样返回。 */
  fun rehostLegacyAttachment(src: String, base: String): String

  /** 换成缩略图地址(「图片加载策略」省流量那两档)。 */
  fun thumbnailUrl(url: String, base: String): String

  /** 剥掉结尾的缩略图后缀,还原原图路径;没有后缀时原样返回。 */
  fun stripThumbnailSuffix(src: String): String

  /** 从图片地址取一个能直接落盘的文件名(保存到相册 / 分享的下载中转都用它)。 */
  fun imageFileName(url: String): String

  /** 按文件名猜 MIME(系统分享面板靠它挑目标应用)。 */
  fun imageMimeType(fileName: String): String
}

/**
 * 拼附件地址要的、正文本身给不出的两项。
 *
 * @property base `_ATTACH_BASE_VIEW` 归一后的基址(见 [AttachmentUrls.normalizeAttachBase])
 * @property postedAt 所在楼层的发帖时间(秒级 unix),`[noimg]` 补日期目录用;没有就不猜
 */
data class AttachmentUrlOptions(val base: String, val postedAt: Long? = null)

/** 缩略图后缀(旧 Android 客户端 `ForumImageDecoder` 的同一张表)。 */
private val THUMBNAIL_SUFFIXES =
  listOf(".thumb_ss.jpg", ".thumb_s.jpg", ".thumb.jpg", ".medium.jpg")

/** 服务端给的缩略图后缀,展示时要剥掉换回原图。 */
const val THUMBNAIL_SUFFIX: String = ".thumb.jpg"

/** 落盘文件名里不敢要的字符(Android 文件系统 + MediaStore 的交集)。 */
private val UNSAFE_FILENAME_CHARS = Regex("""[\\/:*?"<>|\s%#]+""")

/** 常见图片扩展名 → MIME。落不进表的按 jpeg 兜底——NGA 附件绝大多数是 jpg。 */
private val IMAGE_MIME_TYPES: Map<String, String> = mapOf(
  "jpg" to "image/jpeg",
  "jpeg" to "image/jpeg",
  "png" to "image/png",
  "gif" to "image/gif",
  "webp" to "image/webp",
  "bmp" to "image/bmp",
  "avif" to "image/avif",
)

/**
 * 响应里没有 `_ATTACH_BASE_VIEW` 时的兜底基址。只在字段缺失(被封、Web 反解、旧缓存)
 * 时用得上:宁可拿一个可能过期的域名去试,也好过整楼图片全渲染不出来。
 */
const val ATTACH_BASE_FALLBACK: String = "img.nga.cn/attachments"

/** NGA 的日期目录按论坛所在时区(UTC+8)分,不能跟着设备时区走。 */
private const val NGA_UTC_OFFSET_SECONDS = 8 * 60 * 60L

/** 已经带日期目录的相对路径,例如 `mon_202608/07/x.jpg`。 */
private val DATED_PATH_PATTERN = Regex("""^mon_\d{6}/""")

/**
 * 认「这是不是 NGA 自己的附件域名」。域名换过好几次,老域名的地址还留在老帖正文里。
 * 这张表只用来**认**,不用来拼——真正的目标基址仍然只从 `_ATTACH_BASE_VIEW` 来(ADR-0002)。
 */
private val NGA_ATTACH_HOST =
  Regex("""(?:^|\.)(?:nga\.cn|ngacn\.cc|nga\.178\.com)$""", RegexOption.IGNORE_CASE)

/** 绝对地址里的 `<host>/attachments/<路径>`;协议相对(`//`)的写法也收。 */
private val ABSOLUTE_ATTACHMENT =
  Regex("""^(?:https?:)?//([^/]+)/attachments/(.+)$""", RegexOption.IGNORE_CASE)

private val LEADING_SLASHES = Regex("""^/+""")
private val TRAILING_SLASHES = Regex("""/+$""")
private val SCHEME_PREFIX = Regex("""^https?://""", RegexOption.IGNORE_CASE)

object DefaultAttachmentUrls : AttachmentUrls {

  /**
   * 相对路径的两种形态:`[img]./mon_202608/07/x.jpg[/img]` 自带日期目录,
   * 而 `[noimg]./-7Qd36d-….jpg[/noimg]` 没有——后者要按发帖时间补 `mon_YYYYMM/DD/`
   * 才能取到图(实测缺前缀的地址是 404)。
   */
  override fun attachmentUrl(
    src: String,
    needsAttachBase: Boolean,
    options: AttachmentUrlOptions,
  ): String {
    if (!needsAttachBase) return rehostLegacyAttachment(src, options.base)

    var path = LEADING_SLASHES.replace(stripThumbnailSuffix(src), "")
    val postedAt = options.postedAt
    if (postedAt != null && !DATED_PATH_PATTERN.containsMatchIn(path)) {
      path = "${datedDirectory(postedAt)}/$path"
    }
    return "${options.base}/$path"
  }

  /**
   * 服务端给的是不带协议的 `img.nga.cn/attachments`。这里**保留整段路径**,
   * 而不是像旧 Android 客户端那样只取 `split("/")[0]` 再拼死的 `/attachments`——
   * 那等于把路径换个地方硬编码。服务端给 http 也要升到 https。
   */
  override fun normalizeAttachBase(raw: String?): String {
    val value = raw?.trim().orEmpty()
    val base = value.ifEmpty { ATTACH_BASE_FALLBACK }
    val withoutScheme = SCHEME_PREFIX.replace(base, "")
    return "https://${TRAILING_SLASHES.replace(withoutScheme, "")}"
  }

  /**
   * 版头这类多年不动的帖子里,图片是绝对地址而不是 `./` 相对路径,例如
   * `[img]https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-….png[/img]`。
   * `img.nga.178.com` 已经停了(TLS 握手直接失败,2026-08-08 实测),而同一条
   * `mon_202006/03/…` 路径挂在响应给的 `img.nga.cn/attachments` 下仍然是 200——
   * 所以只要地址落在 NGA 的 `/attachments/` 目录里,就换成响应给的基址再拼。
   */
  override fun rehostLegacyAttachment(src: String, base: String): String {
    val match = ABSOLUTE_ATTACHMENT.matchEntire(src) ?: return src
    // 端口不影响判定,取主机名部分即可
    val host = match.groupValues[1].substringBefore(':')
    if (!NGA_ATTACH_HOST.containsMatchIn(host)) return src
    return "$base/${match.groupValues[2]}"
  }

  /**
   * `mon_YYYYMM/DD`(UTC+8),`[noimg]` 的相对路径缺的就是这一段。
   *
   * 手算而不是用 `java.time`:core 层要能在 JVM 单测里裸跑,而这段算术
   * (民用历、无闰秒、UTC+8 固定偏移)本来就没有时区库的份 —— TS 原件也是
   * `new Date(ms + 8h).getUTC*()` 这么干的。
   */
  private fun datedDirectory(postedAt: Long): String {
    val days = Math.floorDiv(postedAt + NGA_UTC_OFFSET_SECONDS, 86_400L)
    // 1970-01-01 起的天数 → 民用历。算法同 java.time.LocalDate.ofEpochDay。
    var zeroDay = days + 719_468L
    val era = Math.floorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    zeroDay = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val marchMonth = (5 * zeroDay + 2) / 153
    val day = (zeroDay - (marchMonth * 306 + 5) / 10 + 1).toInt()
    val month = (if (marchMonth < 10) marchMonth + 3 else marchMonth - 9).toInt()
    val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
    return "mon_$year${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}"
  }

  override fun stripThumbnailSuffix(src: String): String {
    for (suffix in THUMBNAIL_SUFFIXES) {
      if (src.endsWith(suffix)) return src.substring(0, src.length - suffix.length)
    }
    return src
  }

  /**
   * 只对挂在当前附件基址下的图动手:站外图床没有这套后缀约定,加上去就是 404。
   * 先剥再加,所以对已经是缩略图的地址是幂等的。
   */
  override fun thumbnailUrl(url: String, base: String): String {
    if (!url.startsWith("$base/")) return url
    return stripThumbnailSuffix(url) + THUMBNAIL_SUFFIX
  }

  /**
   * 取路径最后一段、去掉查询串、剥掉缩略图后缀(存的是原图,名字不该带 `.thumb`)、
   * 替换文件系统不认的字符;没有认得出的图片扩展名时补 `.jpg`——
   * MediaStore 靠扩展名认类型,裸哈希名会存成「未知文件」进不了相册。
   */
  override fun imageFileName(url: String): String {
    val path = url.takeWhile { it != '?' && it != '#' }
    val lastSegment = path.substring(path.lastIndexOf('/') + 1)
    val base = UNSAFE_FILENAME_CHARS.replace(
      stripThumbnailSuffix(decodeUriComponentSafe(lastSegment)),
      "_",
    )
    val named = if (base == "" || base == "_") "image-${hashOf(url)}" else base
    return if (extensionOf(named) == null) "$named.jpg" else named
  }

  override fun imageMimeType(fileName: String): String {
    val extension = extensionOf(fileName)
    return (if (extension == null) null else IMAGE_MIME_TYPES[extension]) ?: "image/jpeg"
  }

  /** 认得出的图片扩展名(小写),认不出返回 null。 */
  private fun extensionOf(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return null
    val extension = fileName.substring(dot + 1).lowercase()
    return if (IMAGE_MIME_TYPES.containsKey(extension)) extension else null
  }

  /**
   * 站外图床会出现 `%20` 这类转义;转不动的(裸 `%`)原样保留,不能抛。
   *
   * 手写而不是用 `java.net.URLDecoder`:后者把 `+` 当空格(application/x-www-form-urlencoded
   * 的规矩),而 TS 原件用的是 `decodeURIComponent`,`+` 是字面加号。差一个字符就是
   * 「同一张图在两端推出两个文件名」。
   */
  private fun decodeUriComponentSafe(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
      val ch = value[index]
      if (ch == '%') {
        if (index + 2 >= value.length) return value
        val hex = value.substring(index + 1, index + 3)
        val byte = hex.toIntOrNull(16) ?: return value
        bytes.add(byte.toByte())
        index += 3
      } else {
        // 非转义字符按 UTF-8 写回字节流,与 %XX 的字节拼在一起整体解码
        for (b in ch.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
        index += 1
      }
    }
    val raw = bytes.toByteArray()
    val decoded = raw.toString(Charsets.UTF_8)
    // 非法 UTF-8 序列会被替换成 U+FFFD;decodeURIComponent 在这种情况下是抛错的,照 TS 原件退回原串
    return if (decoded.contains('�') && !value.contains('�')) value else decoded
  }

  /** 文件名兜底用的短哈希(djb2)。只求稳定可辨,不求防碰撞。 */
  private fun hashOf(value: String): String {
    var hash = 5381
    for (ch in value) {
      hash = (hash shl 5) + hash + ch.code
    }
    return hash.toUInt().toString(36)
  }
}
