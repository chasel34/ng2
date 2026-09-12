package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.ChildBearing
import com.chasel.ng2n.core.bbcode.LineBreakNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.parseBBCode

/**
 * 把一段 BBCode 压成一行纯文本(RN 侧原件 `src/ui/bbcode/plain-text.ts`)。
 *
 * 给只有一两行位置的地方用——贴条、「我的回复」的摘要:那些正文里常带一整段
 * `[quote][b]Reply to …[/b]` 引用头、图片、表格,连同渲染出来会把那一小块撑爆。
 * 完整形态在楼层里看。
 */
fun plainTextOf(content: String): String = flattenPlainText(parseBBCode(content))
  .replace(WHITESPACE, " ")
  .trim()

private val WHITESPACE = Regex("""\s+""")

/**
 * 只走 `children`,和 TS 的 `'children' in node` 一样——[com.chasel.ng2n.core.bbcode.ListNode]
 * 的 `items` 与 [com.chasel.ng2n.core.bbcode.TableNode] 的 `rows` 故意不进:
 * 贴条摘要里把一整张表拍平成一行字,读起来是一串没有边界的词。
 */
private fun flattenPlainText(nodes: List<BBCodeNode>): String = buildString {
  for (node in nodes) {
    when (node) {
      is TextNode -> append(node.value)
      LineBreakNode -> append(' ')
      is ChildBearing -> append(flattenPlainText(node.children))
      else -> Unit
    }
  }
}
