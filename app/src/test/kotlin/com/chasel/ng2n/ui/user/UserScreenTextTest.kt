package com.chasel.ng2n.ui.user

import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicReply
import com.chasel.ng2n.core.api.UserPostKind
import com.chasel.ng2n.ui.board.dateText
import com.chasel.ng2n.ui.theme.avatarColorAt
import com.chasel.ng2n.ui.theme.avatarColorFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 「我的主题 / 我的回复」与资料页里那几个**纯函数**的口径 ——
 * 与 RN 侧 `src/app/user/posts.tsx`、`src/ui/avatar.tsx` 对拍。
 */
class UserScreenTextTest {

  private fun topic(replyAt: Long?) = Topic(
    tid = 1,
    subject = "标题",
    author = "谁",
    reply = replyAt?.let { TopicReply(pid = 9, content = "正文", postedAt = it) },
  )

  @Test
  fun `两个入口的标题与空态文案与 RN 版逐字一致`() {
    assertEquals("我的主题", titleOf(UserPostKind.TOPICS))
    assertEquals("我的回复", titleOf(UserPostKind.REPLIES))
    assertEquals("还没有发过主题", emptyTextOf(UserPostKind.TOPICS))
    assertEquals("还没有回过帖", emptyTextOf(UserPostKind.REPLIES))
  }

  /** 过期占位条目的 `__P.postdate` 是 0,照 `dateText` 走会显示成 1970-01-01。 */
  @Test
  fun `回复时间为 0 的占位条目显示破折号而不是 1970`() {
    assertEquals("—", replyTimeText(topic(replyAt = 0)))
    assertEquals("—", replyTimeText(topic(replyAt = null)))

    val at = 1_700_000_000L
    assertEquals(dateText(at), replyTimeText(topic(replyAt = at)))
    assertNotEquals("—", replyTimeText(topic(replyAt = at)))
  }

  /**
   * 占位头像取色 —— 散列值与 RN 侧 `avatarColorFor` 同一个数
   * (`hash = (hash*31 + charCode) % 0xffffff`,逐步取模)。
   * 期望档位是拿同一段算式在 JS 口径下算出来的。
   */
  @Test
  fun `同一个 uid 每次都落在同一档色上 且与 RN 版同一档`() {
    assertEquals(avatarColorFor("42"), avatarColorFor("42"))
    // '1' → 49 → 49 % 7 == 0;'42' → 1662 → 1662 % 7 == 3;'60315810' → 8294088 % 7 == 5
    assertEquals(avatarColorAt(0), avatarColorFor("1"))
    assertEquals(avatarColorAt(3), avatarColorFor("42"))
    assertEquals(avatarColorAt(5), avatarColorFor("60315810"))
    // 空 key 也得有一档色(名字拿不到时的兜底),不能抛
    assertEquals(avatarColorAt(0), avatarColorFor(""))
  }
}
