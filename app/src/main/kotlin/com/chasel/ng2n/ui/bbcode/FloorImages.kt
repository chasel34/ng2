package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.bbcode.BBCodeNode

data class ViewerImage(val url: String, val thumbnailUrl: String? = null)

const val ATTACHMENT_IMAGE_KIND: String = com.chasel.ng2n.core.ai.ATTACHMENT_IMAGE_KIND

fun collectFloorImages(
  nodes: List<BBCodeNode>,
  attachments: List<FloorAttachment>,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<ViewerImage> {
  // 缩略图来源随地址第一次出现的位置：正文内联图按地址推导，只在附件里出现的图用附件自带的缩略图。
  val inline = com.chasel.ng2n.core.ai.floorImageUrls(nodes, emptyList(), options, urls).toSet()
  val attached = attachments.filter { it.kind == ATTACHMENT_IMAGE_KIND }.associate { it.url to it.thumbnailUrl }
  return com.chasel.ng2n.core.ai.floorImageUrls(nodes, attachments, options, urls).map { url ->
    val thumbnail = if (url in inline) urls.thumbnailUrl(url, options.base) else attached[url]
    if (thumbnail == null || thumbnail == url) ViewerImage(url) else ViewerImage(url, thumbnail)
  }
}
