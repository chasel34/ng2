package com.chasel.ng2n.core.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `childNodeLists` 的遍历语义。手工移植自 `src/core/bbcode/walk.test.ts`。
 *
 * 金样本只锁得住 `parseBBCode` 的输出(`walk-*` 那 5 条),锁不住「遍历时上哪儿找子节点」
 * —— 而票 11(渲染切段)、票 10/13(骰子复算、引用索引)全靠这一个函数,漏掉一种容器
 * 就是「表格里的字整段不见」。所以这一组用例必须手工在这边留一份。
 */
class WalkTest {

  private fun only(source: String): BBCodeNode {
    val nodes = parseBBCode(source)
    assertEquals(1, nodes.size, "样例应当只解析出一个顶层节点:$source → $nodes")
    return nodes[0]
  }

  /** 走一遍整棵树,把文字接起来——遍历漏了哪种容器,这里就会少一段。 */
  private fun flatten(nodes: List<BBCodeNode>): String = nodes.joinToString("") { node ->
    if (node is TextNode) node.value else flatten(childNodeLists(node).flatten())
  }

  @Test
  fun `普通容器给的是 children`() {
    assertEquals(
      listOf(listOf(TextNode("粗"))),
      childNodeLists(only("[b]粗[/b]")),
    )
  }

  @Test
  fun `列表给的是每一项`() {
    assertEquals(
      listOf(listOf(TextNode("甲")), listOf(TextNode("乙"))),
      childNodeLists(only("[list][*]甲[*]乙[/list]")),
    )
  }

  @Test
  fun `表格给的是每个格子,按行铺平`() {
    val lists = childNodeLists(
      only("[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]"),
    )
    assertEquals(listOf("甲", "乙", "丙"), lists.map(::flatten))
  }

  @Test
  fun `叶子节点没有子节点`() {
    assertEquals(emptyList<List<BBCodeNode>>(), childNodeLists(only("就是一段字")))
    assertEquals(emptyList<List<BBCodeNode>>(), childNodeLists(only("[dice]1d100[/dice]")))
    assertEquals(emptyList<List<BBCodeNode>>(), childNodeLists(only("[img]./a.jpg[/img]")))
  }

  @Test
  fun `拿它走整棵树,各种容器里的字一个都不漏`() {
    val source = "开头[b]粗[/b][quote]引用[/quote][collapse=提要]折叠[/collapse]" +
      "[list][*]甲[/list][table][tr][td]格子[/td][/tr][/table][align=center]居中[/align]"
    assertEquals("开头粗引用折叠甲格子居中", flatten(parseBBCode(source)))
  }
}
