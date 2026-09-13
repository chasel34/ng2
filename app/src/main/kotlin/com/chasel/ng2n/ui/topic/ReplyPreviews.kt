package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.QuoteNode
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.core.local.stripQuoteMarkup
import com.chasel.ng2n.ui.bbcode.BBCodeNodeShape
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.bbcode.replyHeaderRefOf
import com.chasel.ng2n.ui.bbcode.resolveFloorDice
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

internal fun replyHeaderRefs(detail: TopicDetail): Set<QuoteRef> = buildSet {
  fun walk(nodes: List<BBCodeNode>) {
    for (node in nodes) {
      if (node is QuoteNode) continue
      val ref = replyHeaderRefOf(node)
      if (ref != null) add(ref)
      else BBCodeNodeShape.childNodeLists(node).forEach(::walk)
    }
  }
  for (floor in detail.floors + detail.hotReplies) walk(parseBBCode(floor.content))
}

internal fun buildReplyPreviews(
  detail: TopicDetail,
  sources: List<TopicDetail>,
  style: TopicRenderStyle,
  urls: AttachmentUrls,
): ImmutableMap<QuoteRef, FloorRenderModel> {
  val floors = (sources + detail).filter { it.tid == detail.tid }
    .flatMap { source -> (source.hotReplies + source.floors).map { it.pid to (it to source) } }
    .toMap()
  return replyHeaderRefs(detail).mapNotNull { ref ->
    if (ref.tid != null && ref.tid != detail.tid) return@mapNotNull null
    val (floor, source) = floors[ref.pid] ?: return@mapNotNull null
    val nodes = stripQuoteMarkup(parseBBCode(floor.content), BBCodeNodeShape)
    ref to RenderModelBuilder.buildQuotePreview(
      nodes,
      BBCodeRenderOptions(
        attachBase = source.attachBase,
        postedAt = floor.postedAt,
        dice = resolveFloorDice(nodes, DiceSeed(floor.authorId, source.tid, floor.pid)).toImmutableList(),
        colors = style.colors,
        bodyFontSize = style.bodyFontSize,
        bodyLineHeight = style.bodyLineHeight,
        attachmentUrls = urls,
      ),
    )
  }.toMap().toImmutableMap()
}
