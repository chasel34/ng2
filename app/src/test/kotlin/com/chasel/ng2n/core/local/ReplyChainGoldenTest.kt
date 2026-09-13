package com.chasel.ng2n.core.local

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.encodeBBCode
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.longFieldOrNull
import com.chasel.ng2n.golden.runGoldenDomain
import com.chasel.ng2n.ui.bbcode.BBCodeNodeShape
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyChainGoldenTest {

  @Test
  fun `reply-chain 金样本全量对拍`() = runGoldenDomain("reply-chain") {
    fn("extractQuoteRefs") { case ->
      extractQuoteRefs(parseBBCode(case.stringField("text")), BBCodeNodeShape).map { it.toGoldenMap() }
    }
    fn("quoteRefOf") { case -> quoteRefOf(case.firstNode(), BBCodeNodeShape)?.toGoldenMap() }
    fn("isReplyHeaderNode") { case -> isReplyHeaderNode(case.firstNode(), BBCodeNodeShape) }
    fn("replyHeaderRefOf") { case -> replyHeaderRefOf(case.firstNode(), BBCodeNodeShape)?.toGoldenMap() }
    fn("stripQuoteMarkup") { case ->
      stripQuoteMarkup(parseBBCode(case.stringField("text")), BBCodeNodeShape).let(::encodeBBCode)
    }
    fn("buildQuoteIndex") { case -> case.quoteIndex().toGoldenMap() }
    fn("buildReplyChain") { case ->
      buildReplyChain(case.quoteIndex(), case.longField("startPid")).map { it.toGoldenMap() }
    }
    fn("chainDepthOf") { case -> chainDepthOf(case.quoteIndex(), case.longField("startPid")) }
  }

  @Test
  fun `正文里随手贴的 pid 链接不算引用——那是提及,不是回复关系`() {
    val nodes = parseBBCode("看看这楼 [pid=99,45150945,1]Reply[/pid] 说的")
    assertEquals(emptyList(), extractQuoteRefs(nodes, BBCodeNodeShape))
  }

  @Test
  fun `Topic 引用主楼并在未加载时定位第一页`() {
    val reply = floor(45, 45, "[quote]<br/> [tid=45150945]Topic[/tid] [b]Post by 某人[/b]原文[/quote]回复")
    val ref = QuoteRef(pid = 0, tid = 45150945, page = 1)
    assertEquals(listOf(ref), reply.refs)
    val missing = buildReplyChain(buildQuoteIndex(listOf(reply), 45150945), 45)
    assertEquals(ChainNode(0, ChainRole.UPSTREAM, false, ref), missing.first())
    assertEquals(2, missing.size)
    val loaded = buildReplyChain(buildQuoteIndex(listOf(floor(0, 0, "主楼"), reply), 45150945), 45)
    assertEquals(ChainNode(0, ChainRole.UPSTREAM, true, ref), loaded.first())
  }

  @Test
  fun `跨帖主楼引用不接入本帖回复链`() {
    val index = buildQuoteIndex(
      listOf(floor(0, 0, "本帖主楼"), floor(45, 45, "[quote][tid=999]Topic[/tid]其他帖子[/quote]回复")),
      45150945,
    )
    assertEquals(listOf(45L), buildReplyChain(index, 45).map { it.pid })
  }

  @Test
  fun `正文和引用正文中的话题提及不当成主楼引用`() {
    for (text in listOf(
      "看看[tid=45150945]Topic[/tid]",
      "[quote]参考这个帖子[tid=45150945]Topic[/tid][/quote]",
      "[quote][quote][tid=45150945]Topic[/tid]内层[/quote]外层[/quote]",
      "[quote][tid=0]Topic[/tid]无效主题[/quote]",
    )) {
      assertEquals(emptyList(), extractQuoteRefs(parseBBCode(text), BBCodeNodeShape), text)
    }
  }

  @Test
  fun `环引用不死循环——A 引 B、B 引 A`() {
    val index = buildQuoteIndex(
      listOf(
        floor(pid = 1, lou = 1, text = quoteOf(2)),
        floor(pid = 2, lou = 2, text = quoteOf(1)),
      ),
      tid = 45150945,
    )
    assertEquals(listOf(2L, 1L), buildReplyChain(index, 1).map { it.pid })
    assertEquals(2, chainDepthOf(index, 1))
  }

  @Test
  fun `孤楼的链只有它自己`() {
    val index = buildQuoteIndex(listOf(floor(pid = 9, lou = 9, text = "就一句话")), tid = 45150945)
    val chain = buildReplyChain(index, 9)
    assertEquals(1, chain.size)
    assertEquals(ChainRole.CURRENT, chain.single().role)
  }

  private fun quoteOf(pid: Long) =
    "[quote][pid=$pid,45150945,1]Reply[/pid] [b]Post by [uid=1]某人[/uid]:[/b]原话[/quote]"

  private fun floor(pid: Long, lou: Long, text: String) = QuoteIndexFloor(
    pid = pid,
    lou = lou,
    refs = extractQuoteRefs(parseBBCode(text), BBCodeNodeShape),
  )
}

private fun QuoteRef.toGoldenMap(): Map<String, Any?> = buildMap {
  page?.let { put("page", it) }
  put("pid", pid)
  tid?.let { put("tid", it) }
}

private fun ChainNode.toGoldenMap(): Map<String, Any?> = buildMap {
  put("loaded", loaded)
  put("pid", pid)
  ref?.let { put("ref", it.toGoldenMap()) }
  put("role", role.wire)
}

private fun QuoteIndex.toGoldenMap(): Map<String, Any?> = mapOf(
  "loaded" to loaded.sorted(),
  "quotedBy" to quotedBy.keys.sorted().map { pid -> listOf(pid, quotedBy.getValue(pid)) },
  "quotes" to quotes.keys.sorted()
    .map { pid -> listOf(pid, quotes.getValue(pid).map { it.toGoldenMap() }) },
)

private fun GoldenCase.firstNode(): BBCodeNode = parseBBCode(stringField("text")).first()

private fun GoldenCase.quoteIndex(): QuoteIndex {
  val floors = field("floors").jsonArray.map { element ->
    val floor = element.jsonObject
    QuoteIndexFloor(
      pid = floor.getValue("pid").jsonPrimitive.long,
      lou = floor["lou"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
      refs = extractQuoteRefs(
        parseBBCode(floor.getValue("content").jsonPrimitive.content),
        BBCodeNodeShape,
      ),
    )
  }
  return buildQuoteIndex(floors, longFieldOrNull("tid"))
}
