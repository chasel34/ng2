package com.chasel.ng2n.data.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import com.chasel.ng2n.core.net.*
import com.chasel.ng2n.core.net.strategies.topicCacheKeyOf
import com.chasel.ng2n.ui.topic.TopicFixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiSourcePreviewReaderTest {
  private val coordinate = AiSourceCoordinate(10, 20, 1, "floor")
  private fun page(text: String) = TopicDetail(tid = 10, subject = "主题", attachBase = "", totalPages = 1,
    floors = listOf(Floor(pid = 20, lou = 3, authorId = 1, authorKey = "1", content = text)))

  @Test fun everyPreviewReadsCoordinatesAgainAndNeverUsesConversationBody() = runTest {
    var reads = 0
    val reader = AiSourcePreviewReader({ params ->
      assertEquals(10L, params.tid); assertEquals(20L, params.pid)
      page("最新原文 ${++reads}")
    }, { emptyList() })
    assertEquals("最新原文 1", reader.read(coordinate).text)
    assertEquals("最新原文 2", reader.read(coordinate).text)
    assertEquals(2, reads)
    assertEquals("request_failed", AiSourcePreviewReader({ page("Room 缓存原文").copy(source = TopicSource.CACHE) }, { emptyList() }).read(coordinate).status)
  }

  @Test fun missingPermissionNetworkAndFilteringStayDistinctAndCancellationPropagates() = runTest {
    for ((error, status) in listOf(
      NgaError(NgaErrorKind.SERVER, "已删除") to "unavailable",
      NgaError(NgaErrorKind.SERVER, "无权限", status = 403) to "permission_denied",
      NgaError(NgaErrorKind.NETWORK, "offline") to "request_failed")) {
      val preview = AiSourcePreviewReader({ throw error }, { emptyList() }).read(coordinate)
      assertEquals(status, preview.status); assertTrue(preview.content.isEmpty())
    }
    assertEquals("unavailable", AiSourcePreviewReader({ page("其他楼层").copy(floors = emptyList()) }, { emptyList() }).read(coordinate).status)
    val filtered = AiSourcePreviewReader({ page("屏蔽词[img]https://example.com/a.jpg[/img]") }, {
      listOf(FilterRule("blocked", FilterRuleKind.KEYWORD, FilterRuleOrigin.LOCAL, "屏蔽词", false))
    }).read(coordinate)
    assertEquals("filtered", filtered.status); assertTrue(filtered.images.isEmpty()); assertTrue(filtered.content.isEmpty())
    try { AiSourcePreviewReader({ throw CancellationException() }, { emptyList() }).read(coordinate); fail() } catch (_: CancellationException) { }
  }

  @Test fun noteIdentitySurvivesReorderingButDeletionDoesNotSubstituteParentOrSibling() = runTest {
    val note = Floor(authorId = 2, authorKey = "2", content = "被引用的贴条[img]https://example.com/note.jpg[/img]")
    val other = Floor(authorId = 3, authorKey = "3", content = "另一条")
    var detail = page("父楼正文[img]https://example.com/parent.jpg[/img]").let {
      it.copy(floors = listOf(it.floors.single().copy(notes = listOf(note, other))),
        users = mapOf("2" to FloorUser(key = "2", name = "贴条作者", rawName = "贴条作者")))
    }
    val context = com.chasel.ng2n.core.ai.buildTopicContext(detail, detail)
    val source = context.sources.first { it.text.startsWith("[贴条] 被引用") }
    val target = coordinate.copy(part = source.part)
    val reader = AiSourcePreviewReader({ detail }, { emptyList() })
    val result = reader.read(target)
    assertEquals("ok", result.status)
    assertTrue(result.isNote)
    assertEquals("贴条作者", result.author)
    assertEquals(note.content, result.content)
    assertEquals(listOf("https://example.com/note.jpg"), result.images)
    detail = detail.copy(floors = listOf(detail.floors.single().copy(notes = listOf(other, note))))
    assertEquals(note.content, reader.read(target).content)
    detail = detail.copy(floors = listOf(detail.floors.single().copy(notes = listOf(other))))
    assertEquals("unavailable", reader.read(target).status)
  }

  @Test fun parentAndLegacyRangesKeepNotesAndFilterEachBodyIndependently() = runTest {
    val notes = listOf(Floor(authorId = 2, authorKey = "2", content = "保留贴条"), Floor(authorId = 3, authorKey = "3", content = "屏蔽贴条[img]https://example.com/secret.jpg[/img]"))
    val detail = page("父楼").let { it.copy(floors = listOf(it.floors.single().copy(notes = notes))) }
    val reader = AiSourcePreviewReader({ detail }, { listOf(FilterRule("block", FilterRuleKind.KEYWORD, FilterRuleOrigin.LOCAL, "屏蔽", false)) })
    val parent = reader.read(coordinate)
    assertEquals("父楼", parent.content)
    assertEquals("保留贴条", parent.notes.first().content)
    assertEquals("filtered", parent.notes.last().status)
    assertTrue(parent.notes.last().images.isEmpty())
    assertTrue(parent.notes.last().content.isEmpty())
    val notePart = com.chasel.ng2n.core.ai.noteSourcePart(notes.last())
    assertEquals("filtered", reader.read(coordinate.copy(part = notePart)).status)
    val legacy = reader.read(coordinate.copy(part = null))
    assertEquals("range", legacy.status)
    assertEquals(parent.notes, legacy.notes)
    val filteredParent = AiSourcePreviewReader({ detail.copy(floors = listOf(detail.floors.single().copy(content = "屏蔽父楼"))) },
      { listOf(FilterRule("block", FilterRuleKind.KEYWORD, FilterRuleOrigin.LOCAL, "屏蔽", false)) }).read(coordinate.copy(part = null))
    assertTrue(filteredParent.bodyFiltered)
    assertTrue(filteredParent.content.isEmpty())
    assertEquals("保留贴条", filteredParent.notes.first().content)
  }

  @Test fun pidNotesCanBeEditedAndLegacyWithoutNotesNeverClaimsExactMatch() = runTest {
    val note = Floor(pid = 91, authorKey = "1", content = "原贴条")
    val part = com.chasel.ng2n.core.ai.noteSourcePart(note)
    val detail = page("父楼").let { it.copy(floors = listOf(it.floors.single().copy(notes = listOf(note.copy(content = "编辑后的贴条"))))) }
    val reader = AiSourcePreviewReader({ detail }, { emptyList() })
    assertEquals("编辑后的贴条", reader.read(coordinate.copy(part = part)).content)
    val legacy = AiSourcePreviewReader({ page("父楼仍存在") }, { emptyList() }).read(coordinate.copy(part = null))
    assertEquals("range", legacy.status)
    assertTrue(legacy.notes.isEmpty())
  }

  @Test fun freshReadPinsAccountAndNeverConsultsTopicCache() = runTest {
    var calls = 0
    val strategy = object : FetchStrategy {
      override val name = "capture"
      override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
        calls++
        assertEquals(Operation.READ, request.operation)
        assertEquals(AccountPolicy.PINNED, request.accountPolicy)
        assertFalse(request.allowTopicCache)
        assertNull(topicCacheKeyOf(request))
        return StrategyOutcome.Ok(NgaResult(parseNgaJson(TopicFixtures.pageEnvelope(floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "新主楼"))), name), name))
      }
    }
    val transport = RecordingTransport { error("Unexpected transport call") }
    val client = testClient(transport, strategies = listOf(strategy))
    repeat(2) { fetchTopicDetail(client, 10, 1, currentAccountFresh = true) }
    assertEquals(2, calls)
  }

  @Test fun singleFloorReadKeepsTheSavedFloorNumberAndPage() = runTest {
    // 按 pid 取单楼时 NGA 返回 lou=0 且页码固定为请求页，会覆盖会话里已保存的坐标。
    val single = page("单楼正文").let { it.copy(page = 1, floors = listOf(it.floors.single().copy(lou = 0))) }
    val preview = AiSourcePreviewReader({ single }, { emptyList() }).read(coordinate.copy(page = 2, floor = 22))
    assertEquals("ok", preview.status)
    assertEquals(22L, preview.floor)
    assertEquals(2, preview.page)
    val fresh = AiSourcePreviewReader({ page("带楼层序号").copy(page = 4) }, { emptyList() }).read(coordinate.copy(page = 2, floor = 22))
    assertEquals(3L, fresh.floor)
    assertEquals(2, fresh.page)
  }
}
