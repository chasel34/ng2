package com.chasel.ng2n.data.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NotificationPolicyTest {

  private data class Item(
    override val id: String,
    override val timestamp: Long,
    val subject: String = "",
  ) : NotificationLike

  private fun item(id: String, timestamp: Long) = Item(id, timestamp)

  @Test
  fun `稳定 ID 是 时间戳-类型-tid-pid`() {
    assertEquals(
      "1786100000-2-44191387-812345678",
      notificationId(NotificationIdParts(1786100000, 2, 44191387, 812345678)),
    )
  }

  @Test
  fun `短信类缺 tid 与 pid 用 0 占位 同一条两次拉取算出同一个 ID`() {
    val first = notificationId(NotificationIdParts(1786080000, 10, 0, 0))
    val second = notificationId(NotificationIdParts(1786080000, 10, 0, 0))
    assertEquals(first, second)
  }

  @Test
  fun `重复拉取不重置已读 合并不碰已读集合`() {
    val feed = listOf(item("a", 300), item("b", 200))
    var items = mergeNotifications(emptyList(), feed)
    var readIds = markRead(emptySet(), listOf("a"))
    assertEquals(1, unreadCount(items, readIds))

    items = mergeNotifications(items, feed)
    assertEquals(1, unreadCount(items, readIds))

    readIds = markRead(readIds, listOf("b"))
    items = mergeNotifications(items, feed)
    assertEquals(0, unreadCount(items, readIds))
  }

  @Test
  fun `新条目正确识别 只把没见过的 ID 插进来 并按时间降序落位`() {
    val existing = mergeNotifications(emptyList(), listOf(item("a", 300), item("b", 200)))
    val incoming = listOf(item("c", 400), item("a", 300), item("b", 200))

    assertEquals(listOf("c"), newNotifications(existing, incoming).map { it.id })
    assertEquals(listOf("c", "a", "b"), mergeNotifications(existing, incoming).map { it.id })
  }

  @Test
  fun `已认识的 ID 保留旧条目 不被新拉取覆盖`() {
    val existing = listOf(Item("a", 300, "旧标题"))
    val merged = mergeNotifications(existing, listOf(Item("a", 300, "新标题")))
    assertEquals(1, merged.size)
    assertEquals("旧标题", merged[0].subject)
  }

  @Test
  fun `返回新集合 不改入参`() {
    val before = setOf("a")
    val after = markRead(before, listOf("b"))
    assertFalse("b" in before)
    assertTrue("a" in after)
    assertTrue("b" in after)
  }

  @Test
  fun `没有新增时返回原集合 引用相等 上层免写盘`() {
    val before = setOf("a")
    assertSame(before, markRead(before, listOf("a")))
    assertSame(before, markRead(before, emptyList()))
  }

  private data class Noti(
    override val id: String,
    val kind: String,
    override val timestamp: Long,
  ) : NotificationLike

  @Test
  fun `按给定顺序分组 组内保持传入顺序 空组不出现`() {
    val order = listOf("reply", "mention", "comment", "message")
    val groups = groupNotifications(
      listOf(
        Noti("m1", "mention", 400),
        Noti("r1", "reply", 300),
        Noti("m2", "mention", 200),
        Noti("c1", "comment", 100),
      ),
      order,
    ) { it.kind }
    assertEquals(listOf("reply", "mention", "comment"), groups.map { it.kind })
    assertEquals(listOf("m1", "m2"), groups[1].items.map { it.id })
  }

  @Test
  fun `不在顺序表里的分类不丢 排在末尾`() {
    val groups = groupNotifications(
      listOf(Noti("r", "reply", 200), Noti("x", "other", 100)),
      listOf("reply"),
    ) { it.kind }
    assertEquals(listOf("reply", "other"), groups.map { it.kind })
  }
}
