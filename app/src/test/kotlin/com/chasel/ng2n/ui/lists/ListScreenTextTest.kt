package com.chasel.ng2n.ui.lists

import com.chasel.ng2n.data.settings.SearchBoardScope
import com.chasel.ng2n.data.settings.SearchHistoryEntry
import com.chasel.ng2n.data.settings.SearchTab
import com.chasel.ng2n.ui.theme.AvatarColors
import com.chasel.ng2n.ui.theme.avatarColorFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListScreenTextTest {

  @Test
  fun `搜板块与搜用户的历史标注是固定两个字`() {
    val entry = SearchHistoryEntry(query = "杂谈")
    assertEquals("版块", historyScopeLabel(SearchTab.BOARDS, entry))
    assertEquals("用户", historyScopeLabel(SearchTab.USERS, entry))
  }

  @Test
  fun `搜主题的历史标注带范围与「包括正文」`() {
    assertEquals(
      "全部板块",
      historyScopeLabel(SearchTab.TOPICS, SearchHistoryEntry(query = "第六感")),
    )
    assertEquals(
      "网事杂谈 · 包括正文",
      historyScopeLabel(
        SearchTab.TOPICS,
        SearchHistoryEntry(
          query = "第六感",
          scope = SearchBoardScope(boardId = 7, kind = "board", name = "网事杂谈"),
          content = true,
        ),
      ),
    )
  }

  @Test
  fun `每个类型码各有各的动词`() {
    assertEquals("回复了你的主题", notificationVerb(1))
    assertEquals("回复了你的楼层", notificationVerb(2))
    assertEquals("给你的主题贴条", notificationVerb(3))
    assertEquals("给你的楼层贴条", notificationVerb(4))
    assertEquals("在帖子里 @ 了你", notificationVerb(7))
    assertEquals("在帖子里 @ 了你", notificationVerb(8))
    assertEquals("发来一条短消息", notificationVerb(10))
    assertEquals("回复了你的短消息", notificationVerb(11))
    assertEquals("评价了你的帖子", notificationVerb(17))
  }

  @Test
  fun `认不出的类型码也要有话说 —— 展示总比悄悄丢掉好`() {
    assertEquals("发来一条通知", notificationVerb(99))
  }

  @Test
  fun `同一个 key 每次都是同一档色`() {
    assertEquals(avatarColorFor("42"), avatarColorFor("42"))
    assertTrue(avatarColorFor("42") in AvatarColors)
  }

  @Test
  fun `散列口径与 RN 版一致`() {
    assertEquals(AvatarColors[3], avatarColorFor("42"))
    assertEquals(AvatarColors[0], avatarColorFor(""))
  }
}
