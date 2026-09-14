package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import org.junit.Test
import org.junit.Assert.*

class TopicContextTest {
  private fun floor(id: Long, body: String = "正文") = Floor(pid = id, lou = id, authorId = 1, authorKey = "1", content = body)
  private fun page(number: Int, vararg floors: Floor) = TopicDetail(tid = 42, subject = "标题", page = number,
    attachBase = "https://img.nga.178.com/attachments/", floors = floors.toList(), users = mapOf("1" to FloorUser("1", 1, "甲", "甲")))

  @Test fun firstAndCurrentPagesAndHotRepliesAreDeduplicated() {
    val first = page(1, floor(0), floor(1)).copy(hotReplies = listOf(floor(1), floor(25)))
    assertEquals(listOf(0L, 1L, 25L), buildTopicContext(first, first).sources.map { it.pid })
    assertEquals(listOf(1), buildTopicContext(first, first).pages)
    val context = buildTopicContext(first, page(2, floor(25), floor(26)))
    assertEquals(listOf(0L, 1L, 25L, 26L), context.sources.map { it.pid })
    assertEquals(listOf(1, 2), context.pages)
  }
  @Test fun preparedRangeCountIsFixedAtPreparationAndDoesNotGrowWithLaterReads() {
    val first = page(1, floor(0), floor(1))
    val context = buildTopicContext(first, first)
    assertEquals(2, context.prepared)
    val grown = context.copy(sources = context.sources + AiSource("s9", 42, 90, 9, 3, "乙", "2026-09-01", "续读楼层", emptyList(), "floor"))
    assertEquals(2, grown.prepared)
    assertEquals(3, grown.sources.size)
  }
  @Test fun firstImageComesFromMainPostThenCurrentPageAndOnlyOneIsSelected() {
    val main = floor(0, "[img]https://example.org/a.jpg[/img][img]https://example.org/b.jpg[/img]")
    val current = page(2, floor(21, "[img]https://example.org/c.jpg[/img]"))
    val result = buildTopicContext(page(1, main), current)
    assertEquals("https://example.org/a.jpg", result.image)
    assertEquals(3, result.sources.sumOf { it.images.size })
    assertEquals("https://example.org/c.jpg", buildTopicContext(page(1, floor(0)), current).image)
  }
  @Test fun floorImagesCountAttachmentsAndMatchTheForumReaderListExactly() {
    val attachments = listOf(
      FloorAttachment(url = "https://img.nga.178.com/attachments/mon_202609/14/inline.jpg", kind = "img"),
      FloorAttachment(url = "https://img.nga.178.com/attachments/mon_202609/14/only-attached.jpg", kind = "img"),
      FloorAttachment(url = "https://img.nga.178.com/attachments/mon_202609/14/notes.zip", kind = "file"))
    val main = floor(0, "[img]./mon_202609/14/inline.jpg[/img]").copy(attachments = attachments)
    val detail = page(1, main).copy(attachBase = "https://img.nga.178.com/attachments")
    val source = buildTopicContext(detail, detail).sources.single()
    // 正文内联图与同一张图片附件只算一次，仅在附件里出现的图仍然计入，非图片附件不计入。
    assertEquals(listOf("https://img.nga.178.com/attachments/mon_202609/14/inline.jpg",
      "https://img.nga.178.com/attachments/mon_202609/14/only-attached.jpg"), source.images)
    val reader = com.chasel.ng2n.ui.bbcode.collectFloorImages(
      com.chasel.ng2n.core.bbcode.parseBBCode(main.content), attachments,
      AttachmentUrlOptions("https://img.nga.178.com/attachments", main.postedAt), DefaultAttachmentUrls)
    assertEquals(source.images, reader.map { it.url })
  }

  @Test fun floorEntryContainsOnlyMainPostAndSelectedPostAndUsesSelectedImage() {
    val first = page(1, floor(0, "[img]https://example.org/main.jpg[/img]"), floor(1)).copy(hotReplies = listOf(floor(40)))
    val result = buildTopicContext(first, page(2, floor(21, "[img]https://example.org/selected.jpg[/img]"), floor(22)), 21)
    assertEquals(listOf(0L, 21L), result.sources.map { it.pid })
    assertEquals("https://example.org/selected.jpg", result.image)
    assertNull(buildTopicContext(first, first, 1).image)
  }
  @Test fun localAndOfficialRulesExcludeTextImagesAndCountUniqueBlockedPosts() {
    val first = page(1, floor(0), floor(1, "秘密[img]https://example.org/private.jpg[/img]"), floor(2, "官方词"))
      .copy(hotReplies = listOf(floor(1, "秘密")))
    val rules = listOf(createFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "秘密"), 0)) + officialFilterRules(BlockWordList(words = listOf("官方词")))
    val result = buildTopicContext(first, first, rules = rules)
    assertEquals(2, result.blocked)
    assertEquals(listOf(0L), result.sources.map { it.pid })
    assertFalse(result.material().contains("秘密"))
    assertFalse(result.material().contains("private.jpg"))
    assertNull(result.image)
  }
  @Test fun anonymousPseudonymAndRawIdentifierDoNotEnterModelMaterial() {
    val anon = FloorUser("-1", name = "甲王李乙赵钱", rawName = "#anony_00000000000000000000000000000000", anonymous = true)
    val first = page(1, floor(0).copy(authorId = -1, authorKey = "-1", content = "甲王李乙赵钱 的观点" )).copy(users = mapOf("-1" to anon))
    val result = buildTopicContext(first, first)
    assertEquals("匿名用户", result.sources.single().author)
    assertFalse(result.material().contains(anon.name))
    assertFalse(result.material().contains("#anony_"))
  }
  @Test fun notesAreFilteredAndLinksAndCollapsedTextRemainAvailable() {
    val first = page(1, floor(0, "[collapse=详情]内文[/collapse][flash]https://example.org/a.mp4[/flash]")
      .copy(notes = listOf(floor(90, "隐藏"), floor(91, "贴条"))))
    val result = buildTopicContext(first, first, rules = listOf(createFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "隐藏"), 0)))
    assertEquals(1, result.blocked)
    assertTrue(result.material().contains("内文"))
    assertTrue(result.material().contains("贴条"))
    assertTrue(result.material().contains("未读取附件内容"))
  }
  @Test fun notesWithoutFloorCoordinatesRemainAttachedToTheirParent() {
    val first = page(1, floor(0), floor(2).copy(notes = listOf(
      Floor(authorKey = "1", content = "第一条贴条"), Floor(authorKey = "1", content = "第二条贴条")) ))
    val result = buildTopicContext(first, first)
    assertEquals(4, result.sources.size)
    assertEquals("floor", result.sources[1].part)
    assertEquals(2, result.sources.takeLast(2).map { it.part }.distinct().size)
    assertTrue(result.sources.takeLast(2).all { it.pid == 2L && it.floor == 2L && it.text.startsWith("[贴条] ") })
  }

  @Test fun filteredFirstPageIsMergedEvenWhenItsPageNumberMatchesTheOrdinaryFirstPage() {
    val first = page(1, floor(0), floor(1))
    val filtered = page(1, floor(74), floor(75))
    val result = buildTopicContext(first, filtered, currentScope = "只看用户 1 · 第 1 页")
    assertEquals(listOf(0L, 1L, 74L, 75L), result.sources.map { it.pid })
    assertTrue(result.material().contains("只看用户 1 · 第 1 页"))
  }

  @Test fun hotReplyOrderDoesNotOverrideTheCurrentPagesFirstImage() {
    val earlier = floor(21, "[img]https://example.org/a.jpg[/img]")
    val later = floor(25, "[img]https://example.org/b.jpg[/img]")
    val first = page(1, floor(0)).copy(hotReplies = listOf(later))
    val current = page(2, earlier, later)
    assertEquals("https://example.org/a.jpg", buildTopicContext(first, current).image)
    val rules = listOf(createFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "a.jpg"), 0))
    val filtered = buildTopicContext(first, current, rules = rules)
    assertEquals("https://example.org/b.jpg", filtered.image)
    assertEquals(1, filtered.blocked)
  }

}
