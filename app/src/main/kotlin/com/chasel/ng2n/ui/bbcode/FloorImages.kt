package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.bbcode.AlbumNode
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ImageNode
import com.chasel.ng2n.core.bbcode.childNodeLists

/**
 * 收集一个楼层的全部图片(RN 侧原件 `src/ui/bbcode/floor-images.ts`)。
 * 查看器要「本楼全部图片列表 + 当前下标」。
 *
 * 口径与渲染器一致:正文里的 `[img]`/`[noimg]`(含嵌在引用块、粗体、相册里的)走
 * `attachmentUrl` 拼地址,附件宫格里图片类的那些排在正文之后——和它们在屏上的出现顺序
 * 相同,查看器里翻页的次序才对得上直觉。
 *
 * 纯函数,不碰组件:点击处拿 URL 反查下标,列表口径错了单测就能钉住。
 */

/** 查看器要的一条。`thumbnailUrl` 与 `url` 相同或没有时不填。 */
data class ViewerImage(val url: String, val thumbnailUrl: String? = null)

/** 附件里哪些算图片(服务端 `type`,实测只见过 `img`)。 */
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
    // 同一张图可能既写在正文又挂在附件里,按第一次出现去重
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
