package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import org.junit.Assert.*
import org.junit.Test

class EntryContextTest {
  private fun topic(id: Long) = Topic(id, subject = "主题 $id", author = "甲", postedAt = 100, replies = id)
  private fun floor(id: Long, text: String = "正文 $id") = Floor(pid = id, lou = id, authorId = 1, authorKey = "1", content = text)
  private fun page(number: Int, vararg floors: Floor) = TopicDetail(tid = 42, subject = "主题", page = number, attachBase = "", floors = floors.toList())
  private val rules = listOf(createFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "屏蔽"), 0))

  @Test fun listKeepsSnapshotOrderAndAppliesCapAfterFiltering() {
    val topics = (412L downTo 1L).map(::topic).toMutableList()
    topics[0] = topics[0].copy(subject = "屏蔽")
    val context = buildListContext(topics, "版块", "发帖时间", rules)
    assertEquals((411L downTo 112L).toList(), context.sources.map { it.tid })
    assertEquals(1, context.blocked)
    assertEquals("412 个主题", context.ranges.first().details.first().second)
    assertEquals("靠前 300 个", context.ranges.first().details[1].second)
    assertTrue(context.sources.all { it.part == "summary" && it.images.isEmpty() })
    assertNull(context.image)
    assertTrue(context.material().contains("未读正文"))
    assertFalse(context.material().contains("主楼不可用"))
  }

  @Test fun shortAndEmptyListsUseActualCountAndExcludeHiddenTextAndPseudonyms() {
    val topics = listOf(topic(3), topic(2).copy(subject = "屏蔽", reply = TopicReply(20, "不应发送", 1)),
      topic(1).copy(anonymous = true, author = "六字匿名假名", subject = "六字匿名假名 的讨论"))
    val context = buildListContext(topics, "版块", "最新回复", rules)
    assertEquals(listOf(3L, 1L), context.sources.map { it.tid })
    assertEquals("2 个", context.ranges[1].amount)
    assertFalse(context.material().contains("不应发送"))
    assertFalse(context.material().contains("六字匿名假名"))
    assertTrue(context.material().contains("匿名用户"))
    assertTrue(buildListContext(emptyList(), "版块", "最新回复").sources.isEmpty())
  }

  @Test fun chainIncludesOnlyRootAndCapturedNodesAcrossPagesAndChoosesOneChainImage() {
    val first = page(1, floor(0, "[img]https://example.org/root.jpg[/img]"), floor(1), floor(2), floor(3))
      .copy(hotReplies = listOf(floor(88)))
    val second = page(2, floor(21, "[img]https://example.org/chain.jpg[/img][img]https://example.org/other.jpg[/img]"), floor(22))
    val nodes = listOf(ChainNode(1, ChainRole.UPSTREAM, true), ChainNode(2, ChainRole.CURRENT, true), ChainNode(21, ChainRole.DOWNSTREAM, true))
    val context = buildChainContext(first, listOf(first, second), nodes)
    assertEquals(listOf(0L, 1L, 2L, 21L), context.sources.map { it.pid })
    assertEquals("https://example.org/chain.jpg", context.image)
    assertEquals(listOf("上游" to "1 楼", "当前" to "2 楼", "下游" to "21 楼"), context.ranges[1].details)
    assertEquals("3 层", context.ranges[1].amount)
    assertEquals(4, context.sources.map { it.id }.distinct().size)
    // 主楼行用状态词与真实楼层号，不与链内的「1 楼」共用同一串字符。
    assertEquals("已计入", context.ranges[0].amount)
    assertEquals(listOf("楼层" to "0 楼"), context.ranges[0].details)
    // 图片总数只统计链内发言，主楼那张单独说明，不混进「0 / N 张」。
    assertEquals("1 / 2 张", context.ranges[2].amount)
    assertEquals("1 张，不参与首轮选取", context.ranges[2].details.single { it.first == "主楼图片" }.second)
  }

  @Test fun chainImageTotalStaysWithinTheChainWhenOnlyTheRootHasImages() {
    val first = page(1, floor(0, "[img]https://example.org/root.jpg[/img]"), floor(1), floor(2))
    val nodes = listOf(ChainNode(1, ChainRole.CURRENT, true), ChainNode(2, ChainRole.DOWNSTREAM, true))
    val context = buildChainContext(first, listOf(first), nodes)
    assertNull(context.image)
    assertEquals("0 张", context.ranges[2].amount)
    assertEquals(listOf("主楼图片" to "1 张，不参与首轮选取"), context.ranges[2].details)
  }

  @Test fun chainCountsBlockedOnceAndReportsMissingWithoutUnrelatedFloors() {
    val first = page(1, floor(0, "屏蔽"), floor(1, "屏蔽[img]https://example.org/private.jpg[/img]"), floor(2), floor(3))
    val nodes = listOf(ChainNode(0, ChainRole.UPSTREAM, true), ChainNode(1, ChainRole.CURRENT, true), ChainNode(99, ChainRole.DOWNSTREAM, false))
    val result = buildChainContext(first, listOf(first), nodes, rules + officialFilterRules(BlockWordList(words = listOf("正文 2"))))
    assertTrue(result.sources.isEmpty())
    assertEquals(2, result.blocked)
    assertNull(result.image)
    assertEquals("1 条", result.ranges.last().amount)
    assertTrue(result.note!!.contains("不能视为完整回复链"))
  }

  @Test fun quickActionsMatchEveryEntryAndKeepFloorPriorContext() {
    assertEquals(listOf("深入某个话题", "梳理讨论分歧", "补充背景"), BuiltinQuickActions.forEntry("列表").map { it.label })
    assertEquals(listOf("梳理分歧", "检查各方论证", "事实核查"), BuiltinQuickActions.forEntry("回复链").map { it.label })
    assertEquals(listOf("事实核查", "批判性思考", "查找前情"), BuiltinQuickActions.forEntry("楼层").map { it.label })
    assertEquals(BuiltinQuickActions.forEntry(false), BuiltinQuickActions.forEntry("主题"))
  }
}
