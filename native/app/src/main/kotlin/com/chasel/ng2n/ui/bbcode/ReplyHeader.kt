package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.BoldNode
import com.chasel.ng2n.core.bbcode.FloorRefNode
import com.chasel.ng2n.core.bbcode.QuoteNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.childNodeLists

/**
 * 「这一楼在回谁」的判据(RN 侧原件 `src/core/local/reply-chain.ts` 的头三个导出)。
 *
 * **归属**:回复链整族(`buildQuoteIndex` / `extractQuoteRefs` / 链的跳转)归**票 10**。
 * 票 11 只需要其中三个纯判据 —— 渲染器要拿它们决定「这个 `[b]` 是不是回复头,画成
 * 引用卡片还是普通粗体」「引用块底部画不画『查看对话链』入口」。
 *
 * **TODO(票 10)**:票 10 落地 `core/local/ReplyChain.kt` 后,把本文件删掉、改 import
 * 那一份(签名保持一致即可)。这里没有第二套语义,是同一段逻辑的临时落点。
 */

/** 一条 `[pid]` 引用指向哪一楼。 */
data class QuoteRef(val pid: Long, val tid: Long? = null, val page: Int? = null)

/** `[pid=a,b,c]` 节点 → 引用。pid 非正整数(空参、坏参)一律不算引用。 */
private fun refOfFloorRefNode(node: FloorRefNode): QuoteRef? {
  val pid = parseIntArg(node.args.getOrNull(0) ?: node.pid) ?: return null
  return QuoteRef(
    pid = pid,
    tid = parseIntArg(node.args.getOrNull(1)),
    page = parseIntArg(node.args.getOrNull(2))?.toInt(),
  )
}

/** 正整数参数;认不出返回 null(照 TS 的 `parseIntArg`)。 */
private fun parseIntArg(raw: String?): Long? {
  val value = raw?.trim()?.toLongOrNull() ?: return null
  return if (value > 0) value else null
}

/**
 * 一段节点里的第一个 `[pid]` 引用。**不进嵌套的 quote**——
 * 引用块里再套一层引用块时,内层的 `[pid]` 是被引用楼自己的引用关系,
 * 算到本楼头上会把祖孙关系错接成父子。
 */
private fun firstFloorRef(nodes: List<BBCodeNode>): QuoteRef? {
  for (node in nodes) {
    if (node is FloorRefNode) {
      val ref = refOfFloorRefNode(node)
      if (ref != null) return ref
      continue
    }
    if (node is QuoteNode) continue
    for (children in childNodeLists(node)) {
      val ref = firstFloorRef(children)
      if (ref != null) return ref
    }
  }
  return null
}

/** 一个引用块指向哪一楼。渲染层用它决定「查看对话链」入口画不画。 */
fun quoteRefOf(node: QuoteNode): QuoteRef? = firstFloorRef(node.children)

/**
 * 一个节点是不是 `[b]Reply to [pid=…]…[/b]` 回复头。
 *
 * 渲染层拿它把回复头画成引用卡片:这种写法没有 `[quote]` 容器,只按节点类型分派的话
 * 它就是正文顶上突兀的一行加粗英文,跟楼主自己说的话糊在一起。
 */
fun isReplyHeaderNode(node: BBCodeNode): Boolean =
  node is BoldNode && firstText(node.children)?.trimStart()?.startsWith("Reply to") == true

/**
 * 回复头指向哪一楼。不是回复头、或头里认不出 `[pid]`(手打的)时返回 null——
 * 跟 [quoteRefOf] 一样,渲染层用它决定「查看对话链」入口画不画。
 */
fun replyHeaderRefOf(node: BBCodeNode): QuoteRef? =
  if (isReplyHeaderNode(node) && node is BoldNode) firstFloorRef(node.children) else null

/** 一段节点里的第一段文字(深度优先)。 */
private fun firstText(nodes: List<BBCodeNode>): String? {
  for (child in nodes) {
    if (child is TextNode) return child.value
    for (children in childNodeLists(child)) {
      val value = firstText(children)
      if (value != null) return value
    }
  }
  return null
}
