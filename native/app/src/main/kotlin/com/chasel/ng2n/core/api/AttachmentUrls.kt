package com.chasel.ng2n.core.api

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
  /** 换成缩略图地址(「图片加载策略」省流量那两档)。 */
  fun thumbnailUrl(url: String, base: String): String

  /** 剥掉结尾的缩略图后缀,还原原图路径;没有后缀时原样返回。 */
  fun stripThumbnailSuffix(src: String): String

  /** 从图片地址取一个能直接落盘的文件名(保存到相册 / 分享的下载中转都用它)。 */
  fun imageFileName(url: String): String

  /** 按文件名猜 MIME(系统分享面板靠它挑目标应用)。 */
  fun imageMimeType(fileName: String): String
}

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

object DefaultAttachmentUrls : AttachmentUrls {

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
