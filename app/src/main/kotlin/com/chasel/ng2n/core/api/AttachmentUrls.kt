package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.bbcode.AttachmentRef

/**
 * 图片管线(票 12)与渲染器(票 11)依赖的附件地址接口。实现体是票 10 的 `Attachments.kt`
 * (全族 7 个纯函数,`attachments` domain 44 条金样本对拍),这里只做委托,不持有第二份实现。
 */
interface AttachmentUrls {
  /** 正文里一张图/附件的最终地址:相对路径拼基址、`[noimg]` 补日期目录、老域名重挂。 */
  fun attachmentUrl(src: String, needsAttachBase: Boolean, options: AttachmentUrlOptions): String

  /** [attachmentUrl] 的便捷重载,直接吃 AST 节点。 */
  fun attachmentUrl(ref: AttachmentRef, options: AttachmentUrlOptions): String =
    attachmentUrl(ref.src, ref.needsAttachBase, options)

  /** `_ATTACH_BASE_VIEW` 归一:去协议、去首尾斜杠,缺失/空值落到兜底基址。 */
  fun normalizeAttachBase(raw: String?): String

  /** 老帖正文里的死域名换到当前基址。 */
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
