package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.encoding.decodeUriComponentOrNull
import java.time.Instant
import java.time.ZoneOffset

const val ATTACH_BASE_FALLBACK = "img.nga.cn/attachments"

private val THUMBNAIL_SUFFIXES = listOf(".thumb_ss.jpg", ".thumb_s.jpg", ".thumb.jpg", ".medium.jpg")

const val THUMBNAIL_SUFFIX = ".thumb.jpg"

private val NGA_ZONE: ZoneOffset = ZoneOffset.ofHours(8)

private val DATED_PATH_PATTERN = Regex("""^mon_\d{6}/""")

private val SCHEME_PREFIX = Regex("^https?://")
private val TRAILING_SLASHES = Regex("/+$")

fun normalizeAttachBase(raw: Any?): String {
  val value = if (raw is String) raw.trim() else ""
  val base = if (value.isEmpty()) ATTACH_BASE_FALLBACK else value
  val withoutScheme = SCHEME_PREFIX.replace(base, "")
  return "https://" + TRAILING_SLASHES.replace(withoutScheme, "")
}

fun stripThumbnailSuffix(src: String): String {
  for (suffix in THUMBNAIL_SUFFIXES) {
    if (src.endsWith(suffix)) return src.substring(0, src.length - suffix.length)
  }
  return src
}

private fun datedDirectory(postedAt: Long): String {
  val shifted = Instant.ofEpochSecond(postedAt).atOffset(NGA_ZONE)
  val month = shifted.monthValue.toString().padStart(2, '0')
  val day = shifted.dayOfMonth.toString().padStart(2, '0')
  return "mon_${shifted.year}$month/$day"
}

private val NGA_ATTACH_HOST =
  Regex("""(?:^|\.)(?:nga\.cn|ngacn\.cc|nga\.178\.com)$""", RegexOption.IGNORE_CASE)

private val ABSOLUTE_ATTACHMENT =
  Regex("""^(?:https?:)?//([^/]+)/attachments/(.+)$""", RegexOption.IGNORE_CASE)

fun rehostLegacyAttachment(src: String, base: String): String {
  val match = ABSOLUTE_ATTACHMENT.find(src) ?: return src
  val host = match.groupValues[1].substringBefore(':')
  if (!NGA_ATTACH_HOST.containsMatchIn(host)) return src
  return "$base/${match.groupValues[2]}"
}

fun thumbnailUrl(url: String, base: String): String {
  if (!url.startsWith("$base/")) return url
  return stripThumbnailSuffix(url) + THUMBNAIL_SUFFIX
}

private val UNSAFE_FILENAME_CHARS = Regex(
  "[\\\\/:*?\"<>|%#\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+",
)

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

fun imageMimeType(fileName: String): String =
  extensionOf(fileName)?.let { IMAGE_MIME_TYPES[it] } ?: "image/jpeg"

private fun extensionOf(fileName: String): String? {
  val dot = fileName.lastIndexOf('.')
  if (dot <= 0) return null
  val extension = fileName.substring(dot + 1).lowercase()
  return if (extension in IMAGE_MIME_TYPES) extension else null
}

private fun decodeUriComponentSafe(value: String): String =
  decodeUriComponentOrNull(value) ?: value

private fun hashOf(value: String): String {
  var hash = 5381L
  for (char in value) {
    val shifted = (hash.toInt() shl 5).toLong()
    hash = (shifted + hash + char.code.toLong()) and 0xffffffffL
  }
  return java.lang.Long.toString(hash, 36)
}

fun attachmentUrl(src: String, needsAttachBase: Boolean, base: String, postedAt: Long? = null): String {
  if (!needsAttachBase) return rehostLegacyAttachment(src, base)

  var path = stripThumbnailSuffix(src).trimStart('/')
  if (postedAt != null && !DATED_PATH_PATTERN.containsMatchIn(path)) {
    path = "${datedDirectory(postedAt)}/$path"
  }
  return "$base/$path"
}
