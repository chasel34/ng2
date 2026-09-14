package com.chasel.ng2n.ui.ai

import com.chasel.ng2n.data.db.AiConversationEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiHistoryTest {
  private fun entry(id: String, kind: String, tid: Long, title: String, source: String) =
    AiConversationEntity(id, title, kind, tid, source, 1, 1, "已完成", "第 1 页", "{}", null)
  @Test fun filtersSearchTitlesAndUsersAndRequireTwoCharacters() {
    val all = listOf(entry("a", "主题", 1, "游戏讨论", "主题 1"), entry("b", "个人", 2, "倾向分析", "用户 Alice"))
    assertEquals(listOf("a"), filterAiHistory(all, "全部", "游戏", null).map { it.id })
    assertEquals(listOf("b"), filterAiHistory(all, "个人", "alice", null).map { it.id })
    assertTrue(filterAiHistory(all, "全部", "不存在", null).isEmpty())
    assertEquals(2, filterAiHistory(all, "全部", "无", null).size)
    assertEquals(listOf("a"), filterAiHistory(all, "全部", "", 1).map { it.id })
  }
  @Test fun groupsUseLocalCalendarDaysAcrossMidnight() {
    val zone = ZoneId.of("Asia/Shanghai")
    val today = LocalDate.of(2026, 9, 13)
    val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
    assertEquals("今天", historyGroup(midnight, today, zone))
    assertEquals("昨天", historyGroup(midnight - 1, today, zone))
    assertEquals("更早", historyGroup(midnight - 86_400_001, today, zone))
  }
}
