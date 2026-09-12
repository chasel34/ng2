package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.AlbumNode
import com.chasel.ng2n.core.bbcode.AlignNode
import com.chasel.ng2n.core.bbcode.AttachNode
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.BoxNode
import com.chasel.ng2n.core.bbcode.CollapseNode
import com.chasel.ng2n.core.bbcode.DiceNode
import com.chasel.ng2n.core.bbcode.DividerNode
import com.chasel.ng2n.core.bbcode.FlashNode
import com.chasel.ng2n.core.bbcode.HeadingNode
import com.chasel.ng2n.core.bbcode.ImageNode
import com.chasel.ng2n.core.bbcode.LineBreakNode
import com.chasel.ng2n.core.bbcode.ListNode
import com.chasel.ng2n.core.bbcode.QuoteNode
import com.chasel.ng2n.core.bbcode.TableNode
import com.chasel.ng2n.core.bbcode.childNodeLists

private fun isBlockType(node: BBCodeNode): Boolean = when (node) {
  is QuoteNode, is ImageNode, DividerNode, is HeadingNode, is AlignNode, is CollapseNode,
  is ListNode, is TableNode, is BoxNode, is DiceNode, is FlashNode, is AttachNode, is AlbumNode,
  -> true
  else -> false
}

fun isBlockNode(node: BBCodeNode): Boolean = isBlockType(node) || isReplyHeaderNode(node)

fun containsBlock(node: BBCodeNode): Boolean =
  isBlockNode(node) || childNodeLists(node).any { list -> list.any(::containsBlock) }

sealed interface Segment {
  data class Inline(val nodes: List<BBCodeNode>) : Segment

  data class Block(val node: BBCodeNode) : Segment
}

fun splitIntoSegments(nodes: List<BBCodeNode>): List<Segment> {
  val segments = ArrayList<Segment>()
  var inline = ArrayList<BBCodeNode>()

  fun flush() {
    if (inline.any { it !== LineBreakNode }) segments.add(Segment.Inline(inline))
    inline = ArrayList()
  }

  for (node in nodes) {
    if (containsBlock(node)) {
      flush()
      segments.add(Segment.Block(node))
    } else {
      inline.add(node)
    }
  }
  flush()
  return segments
}
