package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepLinkGoldenTest {

  @Test
  fun `deep-link 金样本全量对拍`() = runGoldenDomain("deep-link") {
    fn("parseNgaLink") { case -> parseNgaLink(case.inputString()).toGoldenMap() }
    fn("ngaLinkPath") { case -> ngaLinkPath(case.link()) }
  }

  @Test
  fun `拼出来的路径再解一遍不会串味(read_php ↔ 路由参数同构)`() {
    val source = "https://bbs.nga.cn/read.php?tid=45150945&page=3&pid=880123456&fav=1a2b3c"
    val parsed = parseNgaLink(source)
    val link = (parsed as NgaLinkResult.Ok).link
    assertEquals("/topic/45150945?page=3&pid=880123456&fav=1a2b3c", ngaLinkPath(link))
    assertEquals(parsed, parseNgaLink("ng2://read.php?tid=45150945&page=3&pid=880123456&fav=1a2b3c"))
  }

  @Test
  fun `失败原因各有各的人话文案`() {
    assertEquals(NgaLinkFailure.EMPTY, (parseNgaLink("  ") as NgaLinkResult.Failed).reason)
    assertEquals("先粘一条 NGA 链接进来", NgaLinkFailure.EMPTY.message)
    assertEquals("只认 NGA 官方域名的链接", NgaLinkFailure.FOREIGN_HOST.message)
  }
}

private fun NgaLinkResult.toGoldenMap(): Map<String, Any?> = when (this) {
  is NgaLinkResult.Failed -> mapOf("ok" to false, "reason" to reason.wire)
  is NgaLinkResult.Ok -> mapOf("ok" to true, "link" to link.toGoldenMap())
}

private fun NgaLink.toGoldenMap(): Map<String, Any?> = when (this) {
  is NgaLink.Board -> mapOf("kind" to "board", "id" to id, "boardKind" to boardKind.wire)
  is NgaLink.Topic -> buildMap {
    put("kind", "topic")
    put("tid", tid)
    page?.let { put("page", it) }
    pid?.let { put("pid", it) }
    fav?.let { put("fav", it) }
  }
}

private fun GoldenCase.link(): NgaLink {
  val kind = stringField("kind")
  return if (kind == "board") {
    NgaLink.Board(
      id = field("id").jsonPrimitive.long,
      boardKind = if (stringField("boardKind") == "collection") {
        NgaBoardKind.COLLECTION
      } else {
        NgaBoardKind.BOARD
      },
    )
  } else {
    NgaLink.Topic(
      tid = field("tid").jsonPrimitive.long,
      page = inputObject()["page"]?.jsonPrimitive?.long,
      pid = inputObject()["pid"]?.jsonPrimitive?.long,
      fav = stringFieldOrNull("fav"),
    )
  }
}
