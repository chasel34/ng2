package com.chasel.ng2n.ui.common

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.BoardGroup
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.BlockedUser
import com.chasel.ng2n.core.api.NgaNotification
import com.chasel.ng2n.core.api.NotificationKind
import com.chasel.ng2n.ui.about.AboutKeys
import com.chasel.ng2n.ui.filters.FiltersKeys
import com.chasel.ng2n.ui.filters.distinctBlockUsers
import com.chasel.ng2n.ui.filters.distinctBlockWords
import com.chasel.ng2n.ui.home.buildFavoriteRows
import com.chasel.ng2n.ui.home.buildHomeRows
import com.chasel.ng2n.ui.home.HomeRow
import com.chasel.ng2n.ui.lists.dedupeNotifications
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenListKeysTest {

  @Test
  fun `每一屏的静态 key 在同一张表里都不重复`() {
    for ((screen, keys) in SCREEN_LAZY_KEYS) {
      assertEquals(
        emptyList(),
        duplicateKeys(keys),
        "「$screen」屏有重复的 LazyColumn key —— 上真机就是首帧崩",
      )
    }
  }

  @Test
  fun `关于屏页脚的免责声明与行表里那一行不是同一个 key`() {
    assertTrue(AboutKeys.DISCLAIMER != AboutKeys.FOOTER)
    assertContentEquals(
      listOf("header", "source", "links", "diagnostic", "licenses", "disclaimer", "disclaimer-footer"),
      AboutKeys.all,
    )
  }

  @Test
  fun `查重函数按出现顺序报重复的那几个`() {
    assertEquals(emptyList(), duplicateKeys(listOf("a", "b", "c")))
    assertEquals(listOf("b", "a"), duplicateKeys(listOf("a", "b", "b", "a", "b")))
  }

  @Test
  fun `云端屏蔽词的行 key 带前缀,和状态行的字面量撞不上`() {
    val statics = FiltersKeys.all
    for (word in statics) {
      assertTrue(
        FiltersKeys.word(word) !in statics,
        "关键词「$word」的行 key 与状态行撞了",
      )
    }
    assertTrue(FiltersKeys.user(BlockedUser(name = FiltersKeys.HINT)) !in statics)
    assertTrue(FiltersKeys.rule(FiltersKeys.HINT) !in statics)
  }

  @Test
  fun `云端那张表里的重复条目在铺进列表前就去掉`() {
    assertEquals(listOf("代购", "剧透"), distinctBlockWords(listOf("代购", "剧透", "代购")))

    val users = listOf(
      BlockedUser(uid = 42, name = "张三"),
      BlockedUser(uid = 42, name = "张三(改过名)"),
      BlockedUser(name = "李四"),
      BlockedUser(name = "李四"),
    )
    val distinct = distinctBlockUsers(users)
    assertEquals(2, distinct.size)
    assertEquals(emptyList(), duplicateKeys(distinct.map(FiltersKeys::user)))
  }

  @Test
  fun `同一条通知在两个容器里各出现一次时只铺一行`() {
    val one = notification(id = "1700000000-2-1-2")
    val same = one.copy(userName = "另一个视图")
    val other = notification(id = "1700000001-2-1-3")

    val rows = dedupeNotifications(listOf(one, same, other))

    assertEquals(listOf(one, other), rows)
    assertEquals(emptyList(), duplicateKeys(rows.map { it.id }))
  }

  private fun notification(id: String) = NgaNotification(
    id = id,
    type = 2,
    kind = NotificationKind.REPLY,
    userName = "某人",
    subject = "某帖",
    tid = 1,
    pid = 2,
    timestamp = 1_700_000_000,
  )

  @Test
  fun `同一个版块在分组里出现两次时首页只铺一格`() {
    val board = Board(id = 7, kind = BoardKind.BOARD, name = "网事杂谈")
    val category = BoardCategory(
      id = "other",
      name = "其他",
      groups = listOf(BoardGroup(id = "g1", name = "综合", boards = listOf(board, board))),
    )

    val rows = buildHomeRows(category, announcement = null)

    assertEquals(emptyList(), duplicateKeys(rows.map { it.key }))
    assertEquals(1, rows.count { it is HomeRow.Cell })
  }

  @Test
  fun `收藏表里出现两条同 id 的版块时也只铺一格`() {
    val board = Board(id = 7, kind = BoardKind.BOARD, name = "网事杂谈")
    val rows = buildFavoriteRows(
      announcement = null,
      boards = listOf(board, board),
      placeholder = HomeRow.Notice("empty", com.chasel.ng2n.ui.icons.Ng2nIcon.STAR, "还没有收藏"),
    )

    assertEquals(emptyList(), duplicateKeys(rows.map { it.key }))
    assertEquals(1, rows.count { it is HomeRow.Cell })
  }
}
