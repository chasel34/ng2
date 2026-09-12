package com.chasel.ng2n.ui.nav

import com.chasel.ng2n.core.api.BoardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 深链目标 → 导航键(票 17c)。
 *
 * 解析本身归 `core/local/DeepLink.kt`,已有金样本对拍(`DeepLinkGoldenTest`);
 * 这里只盯**映射**这一层:参数有没有原封不动地落到键上、解不出来会不会给 null。
 * RN 侧对应的是 `ngaLinkPath()` 拼出来的那条路由字符串。
 */
class DeepLinkKeysTest {

  @Test
  fun `主题链接的四个参数原样落到 TopicKey 上`() {
    val key = navKeyForLink("https://bbs.nga.cn/read.php?tid=123&page=2&fav=a1b2#pid456Anchor")
    assertEquals(TopicKey(tid = 123, page = 2, pid = 456, fav = "a1b2"), key)
  }

  @Test
  fun `只有 tid 时其余参数缺席 而不是填 0`() {
    assertEquals(TopicKey(tid = 7), navKeyForLink("ng2n://read.php?tid=7"))
  }

  @Test
  fun `版块链接的 fid 走普通版块`() {
    assertEquals(BoardKey(id = 650, kind = BoardKind.BOARD), navKeyForLink("/thread.php?fid=650"))
  }

  /** stid 与 fid 互斥且 stid 优先(CONTEXT.md「合集」)。 */
  @Test
  fun `stid 优先并走合集`() {
    assertEquals(
      BoardKey(id = 88, kind = BoardKind.COLLECTION),
      navKeyForLink("bbs.nga.cn/thread.php?stid=88&fid=650"),
    )
  }

  /** fid 可以是负数(如 -7);合集不会。 */
  @Test
  fun `负数 fid 也认`() {
    assertEquals(BoardKey(id = -7, kind = BoardKind.BOARD), navKeyForLink("thread.php?fid=-7"))
  }

  @Test
  fun `非 NGA 域名不给键`() {
    assertNull(navKeyForLink("https://example.com/read.php?tid=1"))
  }

  @Test
  fun `解不出目标的输入一律给 null 而不是抛`() {
    assertNull(navKeyForLink(""))
    assertNull(navKeyForLink("随便一句话"))
    assertNull(navKeyForLink("ng2n:///"))
    assertNull(navKeyForLink("https://bbs.nga.cn/read.php"))
    assertNull(navKeyForLink("https://bbs.nga.cn/misc.php?tid=1"))
  }
}
