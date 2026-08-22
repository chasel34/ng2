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

/**
 * 排版分段(RN 侧原件 `src/ui/bbcode/segments.ts`):把一串 AST 节点切成
 * 「行内段」与「块级节点」交替的序列。
 *
 * RN 的 `<Text>` 里塞不进 `<View>`,所以图片、引用块、分割线这些必须自己占一行的东西
 * 得先从行内流里摘出来。**Compose 同理**:`AnnotatedString` 里塞不进任意 composable
 * (`inlineContent` 只吃固定尺寸的小块,表情可以、一张不定高的正文图不行),
 * 而且引用卡片/表格要有自己的背景与手势。所以这一层照搬。
 *
 * 纯函数、不碰组件,这样这套判断能单测。
 */

/**
 * 会自己占一行、塞不进一段文字里的节点。
 *
 * 判据是「渲染成什么」而不是「BBCode 里是块还是行内」:`[align]`、`[collapse]`、
 * `[list]`、`[table]`、`[lessernuke]` 这几个要么带自己的框、要么要横向滚动;
 * `[dice]`、`[flash]`、`[attach]`、`[album]` 画的是卡片,同理。
 */
private fun isBlockType(node: BBCodeNode): Boolean = when (node) {
  is QuoteNode, is ImageNode, DividerNode, is HeadingNode, is AlignNode, is CollapseNode,
  is ListNode, is TableNode, is BoxNode, is DiceNode, is FlashNode, is AttachNode, is AlbumNode,
  -> true
  else -> false
}

/**
 * 块级判断。除了上面那张表,还有一种「按内容认」的:NGA 快速回复塞在正文开头的
 * `[b]Reply to [pid=…]Reply[/pid] Post by 谁 (时间)[/b]` 回复头——BBCode 上它只是个
 * `[b]`,渲染上却跟 `[quote]` 一样是张引用卡片,所以得单独占一块。
 */
fun isBlockNode(node: BBCodeNode): Boolean = isBlockType(node) || isReplyHeaderNode(node)

/**
 * 一个节点里(含各层后代)有没有必须自己占一行的东西。
 *
 * 只看顶层是不够的:NGA 上 `[align=center][img]…[/img][/align]`、`[b][img]…[/b]`
 * 这种**图片裹在行内标签里**的写法极常见,漏判就会把那张图塞进文字流然后整个消失。
 *
 * **与 RN 版的一处偏离**:RN 那边挂了一张 `WeakMap` 记忆表,因为它的渲染层会把同一棵
 * 子树反复交回 `BBCodeBody` 再切一次段(引用块、折叠块、表格单元格各递归一遍)。
 * 这里不需要:渲染模型是**一次性建好**的([RenderModelBuilder]),每棵子树只走一遍,
 * 记忆表没有命中的机会,徒增一张表。RN 那条记忆化的回归用例因此不移植——
 * 它锁的是「反复问只遍历一次」,而这边根本不反复问。
 */
fun containsBlock(node: BBCodeNode): Boolean =
  isBlockNode(node) || childNodeLists(node).any { list -> list.any(::containsBlock) }

/** 切段的产物。 */
sealed interface Segment {
  data class Inline(val nodes: List<BBCodeNode>) : Segment

  /** 块级节点;可能是行内标签裹着块级内容,由建模器递归展开。 */
  data class Block(val node: BBCodeNode) : Segment
}

/** 切段。裹着块级内容的行内标签一并升格成块。 */
fun splitIntoSegments(nodes: List<BBCodeNode>): List<Segment> {
  val segments = ArrayList<Segment>()
  var inline = ArrayList<BBCodeNode>()

  fun flush() {
    // 只剩换行的段不值得占一块:块级元素之间本来就有间距
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
