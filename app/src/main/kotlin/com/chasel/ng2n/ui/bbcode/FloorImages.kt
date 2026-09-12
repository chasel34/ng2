package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.bbcode.AlbumNode
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ImageNode
import com.chasel.ng2n.core.bbcode.childNodeLists

data class ViewerImage(val url: String, val thumbnailUrl: String? = null)

const val ATTACHMENT_IMAGE_KIND: String = "img"

fun collectFloorImages(
  nodes: List<BBCodeNode>,
  attachments: List<FloorAttachment>,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<ViewerImage> {
  val seen = LinkedHashSet<String>()
  val images = ArrayList<ViewerImage>()

  fun push(url: String, thumbnail: String?) {
    if (!seen.add(url)) return
    images.add(
      if (thumbnail == null || thumbnail == url) ViewerImage(url) else ViewerImage(url, thumbnail),
    )
  }

  fun visit(list: List<BBCodeNode>) {
    for (node in list) {
      when (node) {
        is ImageNode -> {
          val url = urls.attachmentUrl(node, options)
          push(url, urls.thumbnailUrl(url, options.base))
        }
        is AlbumNode -> for (url in albumImageUrls(node.value, options, urls)) {
          push(url, urls.thumbnailUrl(url, options.base))
        }
        else -> for (children in childNodeLists(node)) visit(children)
      }
    }
  }
  visit(nodes)

  for (attachment in attachments) {
    if (attachment.kind != ATTACHMENT_IMAGE_KIND) continue
    push(attachment.url, attachment.thumbnailUrl)
  }

  return images
}
