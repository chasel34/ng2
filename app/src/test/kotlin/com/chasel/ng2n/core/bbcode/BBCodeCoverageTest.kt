package com.chasel.ng2n.core.bbcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BBCodeCoverageTest {

  private val samples: Map<String, String> = linkedMapOf(
    "text" to "一段字",
    "linebreak" to "上<br/>下",
    "bold" to "[b]粗[/b]",
    "italic" to "[i]斜[/i]",
    "underline" to "[u]下划线[/u]",
    "strike" to "[del]删除线[/del]",
    "color" to "[color=red]红[/color]",
    "size" to "[size=120%]大[/size]",
    "font" to "[font=宋体]宋体[/font]",
    "code" to "[code]const a = 1[/code]",
    "link" to "[url=https://example.test]站外[/url]",
    "userRef" to "[uid=123]某人[/uid]",
    "topicRef" to "[tid]45150945[/tid]",
    "floorRef" to "[pid=1,2,3]Reply[/pid]",
    "mention" to "[@某人]",
    "smiley" to "[s:ac:blink]",
    "quote" to "[quote]引用[/quote]",
    "image" to "[img]./mon_202608/07/a.jpg[/img]",
    "divider" to "======",
    "heading" to "===标题===",
    "align" to "[align=center]居中[/align]",
    "collapse" to "[collapse=提要]藏起来的话[/collapse]",
    "list" to "[list][*]甲[*]乙[/list]",
    "table" to "[table][tr][td]甲[/td][td]乙[/td][/tr][/table]",
    "box" to "[lessernuke]处罚说明[/lessernuke]",
    "dice" to "[dice]1d100[/dice]",
    "flash" to "[flash=video]./a.mp4[/flash]",
    "attach" to "[attach]./a.zip[/attach]",
    "album" to "[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]",
  )

  private fun typesIn(nodes: List<BBCodeNode>, found: MutableSet<String> = linkedSetOf()): Set<String> {
    for (node in nodes) {
      found += discriminatorOf(node)
      for (children in childNodeLists(node)) typesIn(children, found)
    }
    return found
  }

  private fun discriminatorOf(node: BBCodeNode): String =
    (encodeBBCode(listOf(node)) as JsonArray)[0].jsonObject.getValue("type").jsonPrimitive.content

  @Test
  fun `清单正好 29 种节点`() {
    assertEquals(29, samples.size)
  }

  @Test
  fun `每一段样例都解析得出它那一种节点`() {
    val missing = samples.filterNot { (type, source) -> type in typesIn(parseBBCode(source)) }
    assertTrue(missing.isEmpty(), "这些样例没解析出对应节点:${missing.keys}")
  }

  @Test
  fun `全部样例拼成一段长正文,解析出的类型不超出清单`() {
    val all = typesIn(parseBBCode(samples.values.joinToString("<br/>")))
    assertEquals(samples.keys, all)
  }

  @Test
  fun `不认识的标签原样透传成文字,不会凭空多出节点类型`() {
    val nodes = parseBBCode("[randomblock]抽奖[/randomblock]")
    assertEquals(setOf("text"), typesIn(nodes))
    assertTrue(nodes.filterIsInstance<TextNode>().joinToString("") { it.value }.contains("[randomblock]"))
  }

  @Test
  fun `不支持的标签一律降级成纯文本`() {
    for (tag in listOf("pre", "hide", "spoiler", "randomblock", "email")) {
      val nodes = parseBBCode("[$tag]内容[/$tag]")
      assertEquals(
        listOf(TextNode("[$tag]内容[/$tag]")),
        nodes,
        "[$tag] 不该被认成标签",
      )
    }
  }

  @Test
  fun `AST 序列化成 JSON 后可以原样读回来（帖子缓存往返）`() {
    val ast = parseBBCode(samples.values.joinToString("<br/>"))
    assertEquals(ast, decodeBBCode(encodeBBCodeToString(ast)))
  }

  @Test
  fun `AST 里不出现任何写死的域名`() {
    val json = encodeBBCodeToString(parseBBCode("[img]./a.jpg[/img]"))
    assertTrue(
      !Regex("nga|178|http", RegexOption.IGNORE_CASE).containsMatchIn(json),
      "附件域名要由渲染层从 __GLOBAL._ATTACH_BASE_VIEW 现取,解析器不许硬编码:$json",
    )
  }

  @Test
  fun `解析器不复算骰子结果,只给出表达式`() {
    val node = (encodeBBCode(parseBBCode("[dice]1d100[/dice]")) as JsonArray)[0] as JsonObject
    assertEquals(setOf("type", "expression"), node.keys)
  }
}
