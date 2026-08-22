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

/**
 * `reply-chain` domain 全量对拍(52 条)。
 *
 * `input` 里的 `text` / `floors[].content` 是楼层正文 BBCode 原文,README 要求
 * 先 `parseBBCode` 再喂给被测函数。票 13 接上了正式解析器与
 * `ui/bbcode/BBCodeShapeAdapter.kt` 的 [BBCodeNodeShape] 适配器 ——
 * **`core/local` 的函数本身一个字没动**(票 10 的设计意图)。
 *
 * `buildQuoteIndex` 的期望值把 Map/Set 拍平成 `{ quotes, quotedBy, loaded }`,键按升序
 * (JSON 装不下 Map);TS 侧返回的是插入序的 Map,升序是导出器的规范化,所以排序在这里做。
 */
class ReplyChainGoldenTest {

  @Test
  fun `reply-chain 金样本全量对拍`() = runGoldenDomain("reply-chain") {
    fn("extractQuoteRefs") { case ->
      extractQuoteRefs(parseBBCode(case.stringField("text")), BBCodeNodeShape).map { it.toGoldenMap() }
    }
    // 这三个收的是 parseBBCode(text)[0] —— 整段正文的第一个节点
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

  // --- 手工移植:`reply-chain.test.ts` 里金样本没单列的判据 ---------------------

  @Test
  fun `正文里随手贴的 pid 链接不算引用——那是提及,不是回复关系`() {
    val nodes = parseBBCode("看看这楼 [pid=99,45150945,1]Reply[/pid] 说的")
    assertEquals(emptyList(), extractQuoteRefs(nodes, BBCodeNodeShape))
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

// ---------------------------------------------------------------------------
// 金样本 JSON ↔ 领域类型
// ---------------------------------------------------------------------------

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

/** README:Map/Set 拍平成表,键按升序。 */
private fun QuoteIndex.toGoldenMap(): Map<String, Any?> = mapOf(
  "loaded" to loaded.sorted(),
  "quotedBy" to quotedBy.keys.sorted().map { pid -> listOf(pid, quotedBy.getValue(pid)) },
  "quotes" to quotes.keys.sorted()
    .map { pid -> listOf(pid, quotes.getValue(pid).map { it.toGoldenMap() }) },
)

/** `quoteRefOf` / `isReplyHeaderNode` / `replyHeaderRefOf` 收的是整段正文的第一个节点。 */
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
