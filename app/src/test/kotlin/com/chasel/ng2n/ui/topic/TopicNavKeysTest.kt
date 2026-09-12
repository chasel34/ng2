package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.data.history.HistoryEntry
import com.chasel.ng2n.ui.lists.historyTopicKey
import com.chasel.ng2n.ui.nav.TopicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopicNavKeysTest {

  private val host = "https://bbs.nga.cn"

  @Test
  fun `用网页版打开 —— 站内兜底屏的键带着当前页码与 fav 码`() {
    val key = TopicKey(tid = 47406116, title = "测试主题", fav = "abc123")
    val web = topicWebKey(key, page = 3, host = host)
    assertEquals("https://bbs.nga.cn/read.php?tid=47406116&page=3&fav=abc123", web.url)
    assertEquals("测试主题", web.title)
  }

  @Test
  fun `用网页版打开 —— 没有 fav 码就不带那一段`() {
    val web = topicWebKey(TopicKey(tid = 47406116), page = 1, host = host)
    assertEquals("https://bbs.nga.cn/read.php?tid=47406116&page=1", web.url)
    assertNull(web.title, "键上没标题、这一帖也还没加载出来,就先不给标题")
  }

  @Test
  fun `用网页版打开 —— 键上没标题时退到这一帖真正的标题`() {
    val web = topicWebKey(TopicKey(tid = 47406116), page = 2, host = host, subject = "从正文里认出来的标题")
    assertEquals("从正文里认出来的标题", web.title)
  }

  @Test
  fun `用网页版打开 —— 域名走设置里选的那个,原生被封往往是整个域名被封`() {
    val web = topicWebKey(TopicKey(tid = 1), page = 1, host = "https://ngabbs.com")
    assertEquals("https://ngabbs.com/read.php?tid=1&page=1", web.url)
  }

  @Test
  fun `历史条目重新打开 —— 带上进度楼层,别让「读到 96 楼」点进去落在第 1 页顶部`() {
    val entry = HistoryEntry(
      tid = 45150945,
      subject = "测试主题",
      favCode = "abc123",
      lastFloor = 96,
      maxFloor = 120,
      updatedAt = 0,
    )
    val key = historyTopicKey(entry)
    assertEquals(45150945L, key.tid)
    assertEquals("测试主题", key.title)
    assertEquals("abc123", key.fav)
    assertEquals(96L, key.floor)
    assertNull(key.page, "页码由 TopicViewModel 按每页 20 楼估;历史里没存 rowsPerPage")
  }

  @Test
  fun `历史条目重新打开 —— 只读过主楼就不带楼号,那本来就是第 1 页顶部`() {
    val entry = HistoryEntry(tid = 45150945, subject = "测试主题", lastFloor = 0, updatedAt = 0)
    assertNull(historyTopicKey(entry).floor)
  }
}
