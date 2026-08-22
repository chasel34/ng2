package com.chasel.ng2n.data.history

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `src/core/local/history.test.ts` 的手工移植(票 14 验收项①)。
 */
class HistoryPolicyTest {

  private fun entry(
    tid: Long,
    subject: String = "主题 $tid",
    author: String? = null,
    boardName: String? = null,
    favCode: String? = null,
    lastFloor: Int = 0,
    maxFloor: Int = 40,
    updatedAt: Long = 1000,
  ) = HistoryEntry(tid, subject, author, boardName, favCode, lastFloor, maxFloor, updatedAt)

  // ------------------------------------------------------------ upsertHistory

  @Test
  fun `新主题插到最前`() {
    val result = upsertHistory(
      listOf(entry(1)),
      TopicVisit(tid = 2, subject = "新主题", maxFloor = 10),
      2000,
    )
    assertTrue(result.changed)
    assertEquals(listOf(2L, 1L), result.entries.map { it.tid })
    assertEquals("新主题", result.entries[0].subject)
    assertEquals(0, result.entries[0].lastFloor)
    assertEquals(10, result.entries[0].maxFloor)
  }

  @Test
  fun `同主题去重 更新时间与资料并挪到最前 而不是新增条目`() {
    val before = listOf(entry(1), entry(2, lastFloor = 18, updatedAt = 1500))
    val updated = upsertHistory(
      before,
      TopicVisit(tid = 2, subject = "主题 2", boardName = "网事杂谈", maxFloor = 60),
      3000,
    )
    assertEquals(2, updated.entries.size)
    val head = updated.entries[0]
    assertEquals(2L, head.tid)
    assertEquals("网事杂谈", head.boardName)
    assertEquals(18, head.lastFloor)
    assertEquals(60, head.maxFloor)
    assertEquals(3000L, head.updatedAt)
  }

  @Test
  fun `新值缺席时保留旧的元数据 楼层上限只前进`() {
    val before = listOf(entry(1, author = "楼主甲", favCode = "abc", maxFloor = 50))
    val result = upsertHistory(before, TopicVisit(tid = 1, subject = "主题 1", maxFloor = 30), 2000)
    assertEquals("楼主甲", result.entries[0].author)
    assertEquals("abc", result.entries[0].favCode)
    assertEquals(50, result.entries[0].maxFloor)
  }

  @Test
  fun `超过上限时挤掉最老的一条并报告被淘汰的 tid`() {
    val full = (0 until HISTORY_LIMIT).map { index ->
      entry(
        tid = (index + 1).toLong(),
        updatedAt = 1000L + (HISTORY_LIMIT - index),
      )
    }
    val result = upsertHistory(full, TopicVisit(tid = 9999, subject = "第 201 条"), 5000)
    assertEquals(HISTORY_LIMIT, result.entries.size)
    assertEquals(9999L, result.entries[0].tid)
    assertEquals(listOf(HISTORY_LIMIT.toLong()), result.evictedTids)
  }

  // ------------------------------------------------------------ advanceHistoryFloor

  @Test
  fun `楼层前进时更新条目`() {
    val result = advanceHistoryFloor(listOf(entry(1, lastFloor = 3)), 1, 18, 2000)
    assertTrue(result.changed)
    assertEquals(18, result.entries[0].lastFloor)
    assertEquals(2000L, result.entries[0].updatedAt)
  }

  @Test
  fun `楼层没前进就不动 滚动回调很勤 不能每次都写盘`() {
    val before = listOf(entry(1, lastFloor = 18))
    assertFalse(advanceHistoryFloor(before, 1, 5, 2000).changed)
    assertFalse(advanceHistoryFloor(before, 1, 18, 2000).changed)
    // changed 为 false 时必须返回原 List,仓库按引用相等跳过持久化
    assertSame(before, advanceHistoryFloor(before, 1, 5, 2000).entries)
  }

  @Test
  fun `条目不存在时丢弃上报 不造残缺行`() {
    assertFalse(advanceHistoryFloor(emptyList(), 1, 18, 2000).changed)
  }

  @Test
  fun `看到比已知上限更大的楼层时把上限一起抬高`() {
    val result = advanceHistoryFloor(listOf(entry(1, maxFloor = 40)), 1, 55, 2000)
    assertEquals(55, result.entries[0].lastFloor)
    assertEquals(55, result.entries[0].maxFloor)
  }

  @Test
  fun `把条目挪到最前 与重启后按 updatedAt 重排的顺序一致`() {
    val result = advanceHistoryFloor(listOf(entry(1), entry(2)), 2, 9, 2000)
    assertEquals(listOf(2L, 1L), result.entries.map { it.tid })
  }

  // ------------------------------------------------------------ 进度文案

  @Test
  fun `读到最后一楼算读完`() {
    assertTrue(isHistoryFinished(40, 40))
    assertFalse(isHistoryFinished(39, 40))
    assertEquals("读完", historyProgressLabel(40, 40))
  }

  @Test
  fun `只有主楼的主题读过主楼就算读完`() {
    assertEquals("读完", historyProgressLabel(0, 0))
  }

  @Test
  fun `普通进度是读到 N 楼 只看过主楼是读到主楼`() {
    assertEquals("读到 18 楼", historyProgressLabel(18, 40))
    assertEquals("读到主楼", historyProgressLabel(0, 40))
  }

  // ------------------------------------------------------------ pageOfFloor

  @Test
  fun `主楼在第 1 页 每页最后一楼不越页`() {
    assertEquals(1, pageOfFloor(0, 20))
    assertEquals(1, pageOfFloor(19, 20))
    assertEquals(2, pageOfFloor(20, 20))
    assertEquals(3, pageOfFloor(45, 20))
  }

  @Test
  fun `非法的每页行数退到 1 行一页而不是除以零`() {
    assertEquals(4, pageOfFloor(3, 0))
  }

  // ------------------------------------------------------------ formatHistoryTime

  /** 固定时区,免得 CI 与本机的默认时区不一样把「今天/昨天」判翻。 */
  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  private fun at(day: Int, hour: Int, minute: Int): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(zone).toEpochSecond()

  private val now = at(8, 15, 0)

  @Test
  fun `一分钟内是刚刚 一小时内按分钟算`() {
    assertEquals("刚刚", formatHistoryTime(now - 30, now, zone))
    assertEquals("12 分钟前", formatHistoryTime(now - 12 * 60, now, zone))
  }

  @Test
  fun `今天昨天带时刻 前天不带 更早给日期`() {
    assertEquals("今天 09:14", formatHistoryTime(at(8, 9, 14), now, zone))
    assertEquals("昨天 23:41", formatHistoryTime(at(7, 23, 41), now, zone))
    assertEquals("前天", formatHistoryTime(at(6, 12, 0), now, zone))
    assertEquals("2026-08-01", formatHistoryTime(at(1, 12, 0), now, zone))
  }

  @Test
  fun `凌晨刚过零点 昨晚的记录按日历日算昨天而不是按 24 小时窗口`() {
    val midnight = at(8, 0, 10)
    // 隔了 2 小时 29 分:按 24 小时窗口算会说成「今天」,按日历日才是「昨天」
    val lastNight = at(7, 21, 41)
    assertEquals("昨天 21:41", formatHistoryTime(lastNight, midnight, zone))
  }

  @Test
  fun `一小时内始终走相对时间 不因为跨了零点就改叫昨天`() {
    val midnight = at(8, 0, 10)
    val justBefore = at(7, 23, 41)
    assertEquals("29 分钟前", formatHistoryTime(justBefore, midnight, zone))
  }
}
