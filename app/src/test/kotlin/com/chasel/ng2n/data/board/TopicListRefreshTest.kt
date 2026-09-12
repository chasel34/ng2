package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.SubBoard
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 票 36:**下拉刷新不该把已经拿到的版块元信息弄丢**。
 *
 * 现场(fid=-7,登录态)是:进版块 → 版头行 + 子版块 chip 行都在;下拉刷新一次,
 * 两块一起消失,「更多 → 子版块」变空态,**退出重进也不恢复**——`refresh` 把
 * `pages` 整份换成刷新到的那一页,而 `ensureFirstPage` 看见 `pages` 非空就不再拉,
 * 坏状态一直留到进程重启。
 *
 * 判据取「刷新那一发不带 `__F`」这个最坏情形:不管是服务端没下发还是链路上丢了,
 * 到了这一层都是同一种形状,合并语义都得兜住。
 */
class TopicListRefreshTest {

  private val key = TopicListRepository.Key(
    boardId = -7,
    kind = BoardKind.BOARD,
    sort = TopicSort.LAST_POST,
  )

  /** 一页带 `__F`(含版头 + 两个子版块)的主题列表。 */
  private fun pageWithForum(topicTid: Long): String = """
    {"data":{
      "__T":{"0":{"tid":$topicTid,"subject":"帖 $topicTid","author":"作者","replies":3,
        "postdate":1787371200,"lastpost":1787371200}},
      "__F":{"fid":-7,"name":"网事杂谈","topped_topic":47000001,
        "sub_forums":{"7":{"0":7,"1":"网络游戏综合","2":"副标题","3":10,"4":1},
                      "414":{"0":414,"1":"游戏综合讨论","3":11,"4":0}}},
      "__ROWS":100,"__T__ROWS_PAGE":35}}
  """.trimIndent()

  /** 同一页,但**整块 `__F` 缺席**——这就是现场那一发刷新的形状。 */
  private fun pageWithoutForum(topicTid: Long): String = """
    {"data":{
      "__T":{"0":{"tid":$topicTid,"subject":"帖 $topicTid","author":"作者","replies":9,
        "postdate":1787371200,"lastpost":1787371200}},
      "__ROWS":100,"__T__ROWS_PAGE":35}}
  """.trimIndent()

  @Test
  fun 重新进入版块拉最新第一页而详情返回保留分页() = runTest {
    var calls = 0
    val repository = TopicListRepository(testClient(RecordingTransport {
      calls += 1
      ok(pageWithForum(calls.toLong()))
    }))

    repository.loadOnEntry(key, restored = false)
    repository.loadNextPage(key)
    assertEquals(listOf(1L, 2L), repository.stateOf(key).topics.map { it.tid })

    repository.loadOnEntry(key, restored = true)
    assertEquals(2, calls, "详情返回不重新请求或丢弃后续页")
    assertEquals(2, repository.stateOf(key).pages.size)

    repository.loadOnEntry(key, restored = false)
    assertEquals(3, calls, "退出版块后重新进入必须获取最新第一页")
    assertEquals(listOf(3L), repository.stateOf(key).topics.map { it.tid })
    assertEquals(1, repository.stateOf(key).pages.size)
  }

  @Test
  fun 恢复列表时缓存丢失仍会补拉() = runTest {
    var calls = 0
    val repository = TopicListRepository(testClient(RecordingTransport {
      calls += 1
      ok(pageWithForum(1))
    }))
    repository.loadOnEntry(key, restored = true)
    assertEquals(1, calls)
    assertEquals(listOf(1L), repository.stateOf(key).topics.map { it.tid })
  }

  @Test
  fun 下拉刷新后版头与子版块都还在() = runTest {
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) ok(pageWithForum(1)) else ok(pageWithoutForum(2))
    }
    val repository = TopicListRepository(testClient(transport))

    repository.ensureFirstPage(key)
    val first = assertNotNull(repository.stateOf(key).pages.firstOrNull())
    assertEquals(47000001L, first.board?.head)
    assertEquals(2, first.subBoards.size)

    repository.refresh(key)

    val refreshed = assertNotNull(repository.stateOf(key).pages.firstOrNull())
    assertEquals(1, repository.stateOf(key).pages.size, "刷新仍然只留第一页")
    assertEquals(listOf(2L), refreshed.topics.map { it.tid }, "主题本身以刷新那一发为准")
    assertEquals(47000001L, refreshed.board?.head, "版头没了就是票 36 的现场")
    assertEquals("网事杂谈", refreshed.board?.name)
    assertEquals(
      listOf("网络游戏综合", "游戏综合讨论"),
      refreshed.subBoards.map { it.name },
      "子版块整块消失 =「更多 → 子版块」变空态",
    )
  }

  @Test
  fun 刷新到新的版头与子版块时以服务端为准() = runTest {
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) {
        ok(pageWithForum(1))
      } else {
        ok(
          """
          {"data":{"__T":{},
            "__F":{"fid":-7,"name":"网事杂谈","topped_topic":47999999,
              "sub_forums":{"7":{"0":7,"1":"网络游戏综合","3":10,"4":1}}},
            "__ROWS":0,"__T__ROWS_PAGE":35}}
          """.trimIndent(),
        )
      }
    }
    val repository = TopicListRepository(testClient(transport))

    repository.ensureFirstPage(key)
    repository.refresh(key)

    val refreshed = assertNotNull(repository.stateOf(key).pages.firstOrNull())
    assertEquals(47999999L, refreshed.board?.head, "新的有就用新的")
    assertEquals(listOf("网络游戏综合"), refreshed.subBoards.map { it.name })
  }

  @Test
  fun 重试是更狠的一档_不保留旧的版块元信息() = runTest {
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) ok(pageWithForum(1)) else ok(pageWithoutForum(2))
    }
    val repository = TopicListRepository(testClient(transport))

    repository.ensureFirstPage(key)
    repository.retry(key)

    val retried = assertNotNull(repository.stateOf(key).pages.firstOrNull())
    assertNull(retried.board, "retry 先把整条 State 丢掉,回到「什么都没有」")
    assertTrue(retried.subBoards.isEmpty())
  }

  @Test
  fun 翻下一页不会被合并语义碰到() = runTest {
    var call = 0
    val transport = RecordingTransport {
      call += 1
      if (call == 1) ok(pageWithForum(1)) else ok(pageWithoutForum(2))
    }
    val repository = TopicListRepository(testClient(transport))

    repository.ensureFirstPage(key)
    repository.loadNextPage(key)

    val pages = repository.stateOf(key).pages
    assertEquals(2, pages.size)
    assertEquals(47000001L, pages[0].board?.head, "第一页原样留着")
    assertNull(pages[1].board, "第二页照实存,不给它补第一页的 `__F`")
  }

  // ---------------------------------------------------------------------------
  // 合并函数本身
  // ---------------------------------------------------------------------------

  private val board = Board(id = -7, kind = BoardKind.BOARD, fid = -7, name = "网事杂谈", head = 1001)

  private val subBoard = SubBoard(
    id = 7,
    kind = BoardKind.BOARD,
    fid = 7,
    name = "网络游戏综合",
    filterId = 10,
    filterType = 1,
    attributes = 1,
  )

  @Test
  fun 没有上一页时原样返回() {
    val fresh = TopicList(board = null, subBoards = emptyList())
    assertEquals(fresh, keepBoardMeta(previous = null, fresh = fresh))
  }

  @Test
  fun 字段级回落_服务端没给副标题与版头才用旧值() {
    val previous = TopicList(board = board.copy(info = "旧副标题", iconUrl = "icon.png"))
    val fresh = TopicList(board = Board(id = -7, kind = BoardKind.BOARD, fid = -7, name = "网事杂谈"))

    val merged = keepBoardMeta(previous, fresh).board
    assertEquals("旧副标题", merged?.info)
    assertEquals("icon.png", merged?.iconUrl)
    assertEquals(1001L, merged?.head)
  }

  @Test
  fun 换了版块就不合并() {
    val previous = TopicList(board = board, subBoards = listOf(subBoard))
    val fresh = TopicList(board = Board(id = 650, kind = BoardKind.BOARD, fid = 650, name = "原神"))

    val merged = keepBoardMeta(previous, fresh).board
    assertEquals(650L, merged?.id)
    assertNull(merged?.head, "版头是上一个版块的,补上去就串味了")
  }

  @Test
  fun 子版块只在整块缺席时回落() {
    val previous = TopicList(subBoards = listOf(subBoard))
    val other = subBoard.copy(id = 414, fid = 414, name = "游戏综合讨论")

    assertEquals(listOf(subBoard), keepBoardMeta(previous, TopicList()).subBoards)
    assertEquals(
      listOf(other),
      keepBoardMeta(previous, TopicList(subBoards = listOf(other))).subBoards,
    )
  }
}
