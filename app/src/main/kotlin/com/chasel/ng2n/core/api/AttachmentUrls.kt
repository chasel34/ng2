package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.AttachmentRef

interface AttachmentUrls {
  fun attachmentUrl(src: String, needsAttachBase: Boolean, options: AttachmentUrlOptions): String

  fun attachmentUrl(ref: AttachmentRef, options: AttachmentUrlOptions): String =
    attachmentUrl(ref.src, ref.needsAttachBase, options)

  fun normalizeAttachBase(raw: String?): String

  fun rehostLegacyAttachment(src: String, base: String): String

  fun thumbnailUrl(url: String, base: String): String

  fun stripThumbnailSuffix(src: String): String

  fun imageFileName(url: String): String

  fun imageMimeType(fileName: String): String
}

data class AttachmentUrlOptions(val base: String, val postedAt: Long? = null)

object DefaultAttachmentUrls : AttachmentUrls {
  override fun attachmentUrl(src: String, needsAttachBase: Boolean, options: AttachmentUrlOptions): String =
    com.chasel.ng2n.core.api.attachmentUrl(src, needsAttachBase, options.base, options.postedAt)

  override fun normalizeAttachBase(raw: String?): String =
    com.chasel.ng2n.core.api.normalizeAttachBase(raw)

  override fun rehostLegacyAttachment(src: String, base: String): String =
    com.chasel.ng2n.core.api.rehostLegacyAttachment(src, base)

  override fun thumbnailUrl(url: String, base: String): String =
    com.chasel.ng2n.core.api.thumbnailUrl(url, base)

  override fun stripThumbnailSuffix(src: String): String =
    com.chasel.ng2n.core.api.stripThumbnailSuffix(src)

  override fun imageFileName(url: String): String =
    com.chasel.ng2n.core.api.imageFileName(url)

  override fun imageMimeType(fileName: String): String =
    com.chasel.ng2n.core.api.imageMimeType(fileName)
}
