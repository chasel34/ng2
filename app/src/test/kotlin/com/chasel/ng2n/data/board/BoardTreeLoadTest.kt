package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.BoardGroup
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.BoardTree
import com.chasel.ng2n.core.api.HomeAnnouncement
import com.chasel.ng2n.data.settings.BOARD_TREE_TTL_MS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 版块树 24h SWR 的四条分支 + 增量合并 —— 直译 `core/api/board-tree-cache.ts` 的测试意图。
 */
class BoardTreeLoadTest {

  private class FakeStore(var snapshot: BoardTreeSnapshot? = null) : BoardTreeStore {
    var writes = 0
    override suspend fun read(): BoardTreeSnapshot? = snapshot
    override suspend fun write(snapshot: BoardTreeSnapshot) {
      this.snapshot = snapshot
      writes += 1
    }
  }

  private fun board(id: Long, name: String, info: String? = null, icon: String? = null) =
    Board(id = id, kind = BoardKind.BOARD, fid = id, name = name, info = info, iconUrl = icon)

  private fun tree(vararg boards: Board, announcements: List<HomeAnnouncement> = emptyList()) =
    BoardTree(
      categories = listOf(
        BoardCategory("wow", "魔兽世界", listOf(BoardGroup("1", "组", boards.toList()))),
      ),
      announcements = announcements,
    )

  @Test
  fun 缓存在24小时内_一个请求都不发() = runTest {
    val store = FakeStore(BoardTreeSnapshot(tree(board(7, "网事杂谈")), fetchedAt = 1_000))
    var calls = 0

    val result = loadBoardTree(
      store = store,
      fetchTree = { calls += 1; tree(board(7, "改名了")) },
      now = 1_000 + BOARD_TREE_TTL_MS - 1,
    )

    assertEquals(0, calls)
    assertEquals(BoardTreeSource.CACHE, result.source)
    assertEquals("网事杂谈", result.tree.categories[0].groups[0].boards[0].name)
  }

  @Test
  fun 缓存过期就联网并写回() = runTest {
    val store = FakeStore(BoardTreeSnapshot(tree(board(7, "旧名")), fetchedAt = 0))

    val result = loadBoardTree(
      store = store,
      fetchTree = { tree(board(7, "新名")) },
      now = BOARD_TREE_TTL_MS,
    )

    assertEquals(BoardTreeSource.NETWORK, result.source)
    assertEquals("新名", result.tree.categories[0].groups[0].boards[0].name)
    assertEquals(1, store.writes)
    assertEquals(BOARD_TREE_TTL_MS, store.snapshot?.fetchedAt)
  }

  @Test
  fun 设备时钟往回跳一律按过期算() = runTest {
    val store = FakeStore(BoardTreeSnapshot(tree(board(7, "旧名")), fetchedAt = 10_000))
    var calls = 0

    loadBoardTree(store = store, fetchTree = { calls += 1; tree(board(7, "新名")) }, now = 5_000)

    assertEquals(1, calls)
  }

  @Test
  fun 拉失败但有缓存_静默用缓存并带出原因() = runTest {
    val store = FakeStore(BoardTreeSnapshot(tree(board(7, "缓存名")), fetchedAt = 0))
    val boom = IllegalStateException("被封了")

    val result = loadBoardTree(
      store = store,
      fetchTree = { throw boom },
      now = BOARD_TREE_TTL_MS,
    )

    assertEquals(BoardTreeSource.CACHE, result.source)
    assertSame(boom, result.error)
    assertEquals("缓存名", result.tree.categories[0].groups[0].boards[0].name)
    assertEquals(0, store.writes)
  }

  @Test
  fun 拉失败又没缓存_抛出去让首页显示错误态() = runTest {
    val store = FakeStore(null)
    assertFailsWith<IllegalStateException> {
      loadBoardTree(store = store, fetchTree = { throw IllegalStateException("没网") }, now = 0)
    }
  }

  @Test
  fun 强制刷新跳过TTL() = runTest {
    val store = FakeStore(BoardTreeSnapshot(tree(board(7, "旧名")), fetchedAt = 1_000))
    var calls = 0

    val result = loadBoardTree(
      store = store,
      fetchTree = { calls += 1; tree(board(7, "新名")) },
      now = 1_001,
      force = true,
    )

    assertEquals(1, calls)
    assertEquals(BoardTreeSource.NETWORK, result.source)
  }

  @Test
  fun 读缓存抛异常当没缓存处理() = runTest {
    val store = object : BoardTreeStore {
      override suspend fun read(): BoardTreeSnapshot = error("存档坏了")
      override suspend fun write(snapshot: BoardTreeSnapshot) = Unit
    }
    val result = loadBoardTree(store = store, fetchTree = { tree(board(7, "线上")) }, now = 0)
    assertEquals(BoardTreeSource.NETWORK, result.source)
  }

  // ---------------------------------------------------------------- 合并

  @Test
  fun 合并时组成以服务端为准_下线的版块跟着消失() {
    val cached = tree(board(7, "网事杂谈"), board(8, "已下线"))
    val fresh = tree(board(7, "网事杂谈"))
    val merged = mergeBoardTree(cached, fresh)
    assertEquals(listOf(7L), merged.categories[0].groups[0].boards.map { it.id })
  }

  @Test
  fun 服务端这次没给的副标题与图标回落到缓存值() {
    val cached = tree(board(7, "网事杂谈", info = "老副标题", icon = "https://icon/7.png"))
    val fresh = tree(board(7, "网事杂谈"))
    val merged = mergeBoardTree(cached, fresh).categories[0].groups[0].boards[0]
    assertEquals("老副标题", merged.info)
    assertEquals("https://icon/7.png", merged.iconUrl)
  }

  @Test
  fun 服务端给了新值就以新值为准() {
    val cached = tree(board(7, "网事杂谈", info = "老", icon = "https://icon/old.png"))
    val fresh = tree(board(7, "网事杂谈", info = "新", icon = "https://icon/new.png"))
    val merged = mergeBoardTree(cached, fresh).categories[0].groups[0].boards[0]
    assertEquals("新", merged.info)
    assertEquals("https://icon/new.png", merged.iconUrl)
  }

  @Test
  fun 公告整份以服务端为准() {
    val cached = tree(board(7, "a"), announcements = listOf(HomeAnnouncement("1-0", "老公告")))
    val fresh = tree(board(7, "a"), announcements = listOf(HomeAnnouncement("2-0", "新公告")))
    val merged = mergeBoardTree(cached, fresh)
    assertEquals(listOf("2-0"), merged.announcements.map { it.id })
  }

  @Test
  fun 没有缓存时不做合并_直接用线上那棵() = runTest {
    val store = FakeStore(null)
    val result = loadBoardTree(store = store, fetchTree = { tree(board(7, "线上")) }, now = 0)
    assertNull(result.error)
    assertTrue(result.tree.categories.isNotEmpty())
    assertEquals(1, store.writes)
  }
}
