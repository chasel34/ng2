package com.chasel.ng2n.ui.lists

import com.chasel.ng2n.data.settings.SearchBoardScope
import com.chasel.ng2n.data.settings.SearchHistoryEntry
import com.chasel.ng2n.data.settings.SearchTab
import com.chasel.ng2n.ui.theme.AvatarColors
import com.chasel.ng2n.ui.theme.avatarColorFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 票 17a 几处「屏上文案」的纯函数,对照 RN 侧同名实现逐条钉住。
 * 文案错了没有编译错误也没有崩溃,只有用户看着别扭 —— 所以单测里写死。
 */
class ListScreenTextTest {

  // ------------------------------------------------------------ 搜索历史的范围标注

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

  // ------------------------------------------------------------ 通知的动词

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

  // ------------------------------------------------------------ 头像占位底色

  @Test
  fun `同一个 key 每次都是同一档色`() {
    assertEquals(avatarColorFor("42"), avatarColorFor("42"))
    assertTrue(avatarColorFor("42") in AvatarColors)
  }

  /** 散列照抄 TS 那一行,所以两版对同一个 uid 落在同一档上。 */
  @Test
  fun `散列口径与 RN 版一致`() {
    // "42" -> ((0*31+52)*31+50) % 0xffffff = 1662 -> 1662 % 7 = 3
    assertEquals(AvatarColors[3], avatarColorFor("42"))
    // 空串落在第 0 档
    assertEquals(AvatarColors[0], avatarColorFor(""))
  }
}
