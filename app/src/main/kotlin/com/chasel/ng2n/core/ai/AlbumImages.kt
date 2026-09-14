package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.bbcode.AlbumNode
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ImageNode
import com.chasel.ng2n.core.bbcode.childNodeLists

private val TAGGED_PATTERN =
  Regex("""]\s*((?:https?://|\./)[^\[]+?)\s*\[""", RegexOption.IGNORE_CASE)

private val BARE_PATTERN = Regex(
  """(?:^|[^a-zA-Z0-9\-_+=.${'$'};/?:@&#%])((?:https?://|\./)[a-zA-Z0-9\-_+=.${'$'};/?:@&#%]+)""",
  RegexOption.IGNORE_CASE,
)

private val HAS_TAG = Regex("""\[(?:img|url)]""", RegexOption.IGNORE_CASE)

fun albumImageUrls(
  value: String,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<String> {
  val pattern = if (HAS_TAG.containsMatchIn(value)) TAGGED_PATTERN else BARE_PATTERN
  return pattern.findAll(value).map { match ->
    val raw = match.groupValues[1]
    val relative = raw.startsWith("./")
    urls.attachmentUrl(
      src = if (relative) raw.substring(2) else raw,
      needsAttachBase = relative,
      options = options,
    )
  }.toList()
}

const val ATTACHMENT_IMAGE_KIND: String = "img"

// 楼层图片口径：正文内联图（含相册）按出现顺序在前，图片附件在后，按最终地址去重。
// 论坛阅读与 AI 资料共用这一份实现，两边的张数不会出现差异。
fun floorImageUrls(
  nodes: List<BBCodeNode>,
  attachments: List<FloorAttachment>,
  options: AttachmentUrlOptions,
  urls: AttachmentUrls,
): List<String> {
  val collected = LinkedHashSet<String>()
  fun visit(list: List<BBCodeNode>) {
    for (node in list) when (node) {
      is ImageNode -> collected += urls.attachmentUrl(node, options)
      is AlbumNode -> collected += albumImageUrls(node.value, options, urls)
      else -> for (children in childNodeLists(node)) visit(children)
    }
  }
  visit(nodes)
  for (attachment in attachments) if (attachment.kind == ATTACHMENT_IMAGE_KIND) collected += attachment.url
  return collected.toList()
}
