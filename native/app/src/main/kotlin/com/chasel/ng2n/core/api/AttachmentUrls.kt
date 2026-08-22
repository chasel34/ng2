package com.chasel.ng2n.core.api

/**
 * 图片管线(票 12)依赖的附件地址接口。实现体是票 10 的 `Attachments.kt`(全族 7 个纯函数,
 * `attachments` domain 44 条金样本对拍),这里只做委托,不再持有第二份实现。
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

object DefaultAttachmentUrls : AttachmentUrls {
  override fun thumbnailUrl(url: String, base: String): String =
    com.chasel.ng2n.core.api.thumbnailUrl(url, base)

  override fun stripThumbnailSuffix(src: String): String =
    com.chasel.ng2n.core.api.stripThumbnailSuffix(src)

  override fun imageFileName(url: String): String =
    com.chasel.ng2n.core.api.imageFileName(url)

  override fun imageMimeType(fileName: String): String =
    com.chasel.ng2n.core.api.imageMimeType(fileName)
}
