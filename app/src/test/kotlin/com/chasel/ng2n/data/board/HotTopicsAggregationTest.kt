package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.fetchHotTopics
import com.chasel.ng2n.core.local.HOT_WINDOW_HOURS
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.blocked
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotTopicsAggregationTest {

  private val now = 1_787_371_200L

  private fun pageOf(vararg rows: String): String {
    val entries = rows.withIndex().joinToString(",") { (index, row) -> "\"$index\":$row" }
    return """{"data":{"__T":{$entries},"__F":{"fid":7,"name":"网事杂谈"},"__ROWS":100,"__T__ROWS_PAGE":35}}"""
  }

  private fun topic(
    tid: Long,
    replies: Int,
    hoursAgo: Long,
    lastPostHoursAgo: Long = hoursAgo,
    extra: String = "",
  ): String = """{"tid":$tid,"subject":"帖 $tid","author":"作者","replies":$replies,""" +
    """"postdate":${now - hoursAgo * 3600},"lastpost":${now - lastPostHoursAgo * 3600}$extra}"""

  private fun pageIndexOf(url: String): Int =
    URI(url).rawQuery.orEmpty().split("&")
      .firstOrNull { it.startsWith("page=") }
      ?.removePrefix("page=")
      ?.toIntOrNull()
      ?: 1

  @Test
  fun 并发拉五页_跨页去重_按回复数降序() = runTest {
    val transport = RecordingTransport { request ->
      when (pageIndexOf(request.url)) {
        1 -> ok(pageOf(topic(tid = 1, replies = 5, hoursAgo = 1), topic(tid = 2, replies = 30, hoursAgo = 2)))
        2 -> ok(pageOf(topic(tid = 2, replies = 30, hoursAgo = 2), topic(tid = 3, replies = 12, hoursAgo = 3)))
        else -> ok(pageOf())
      }
    }

    val result = fetchHotTopics(
      client = testClient(transport),
      boardId = 7,
      kind = BoardKind.BOARD,
      nowSeconds = now,
    )

    assertEquals(5, result.pagesTried)
    assertEquals(emptyList(), result.failedPages)
    assertEquals(listOf(2L, 3L, 1L), result.topics.map { it.tid })
  }

  @Test
  fun 窗口按发帖时间_老坟被顶起来也不进榜() = runTest {
    val transport = RecordingTransport { request ->
      if (pageIndexOf(request.url) == 1) {
        ok(
          pageOf(
            topic(tid = 1, replies = 3, hoursAgo = 5),
            topic(tid = 2, replies = 999, hoursAgo = HOT_WINDOW_HOURS + 1L, lastPostHoursAgo = 0),
          ),
        )
      } else {
        ok(pageOf())
      }
    }

    val result = fetchHotTopics(testClient(transport), 7, BoardKind.BOARD, now)
    assertEquals(listOf(1L), result.topics.map { it.tid })
  }

  @Test
  fun 合集与镜像行剔掉_外链活动帖也剔掉() = runTest {
    val transport = RecordingTransport { request ->
      if (pageIndexOf(request.url) == 1) {
        ok(
          pageOf(
            topic(tid = 1, replies = 1, hoursAgo = 1),
            topic(tid = 2, replies = 100, hoursAgo = 1, extra = ""","type":32768"""),
            topic(
              tid = 3,
              replies = 100,
              hoursAgo = 1,
              extra = ""","jumpurl":"https://bbs.nga.cn/nuke.php?x=1"""",
            ),
          ),
        )
      } else {
        ok(pageOf())
      }
    }

    val result = fetchHotTopics(testClient(transport), 7, BoardKind.BOARD, now)
    assertEquals(listOf(1L), result.topics.map { it.tid })
  }

  @Test
  fun 部分页失败照出榜单_失败页码带出去() = runTest {
    val transport = RecordingTransport { request ->
      when (pageIndexOf(request.url)) {
        1 -> ok(pageOf(topic(tid = 1, replies = 5, hoursAgo = 1)))
        3 -> blocked()
        else -> ok(pageOf())
      }
    }

    val result = fetchHotTopics(testClient(transport), 7, BoardKind.BOARD, now)
    assertEquals(listOf(3), result.failedPages)
    assertEquals(listOf(1L), result.topics.map { it.tid })
  }

  @Test
  fun 全部页失败才抛错() = runTest {
    val transport = RecordingTransport { blocked() }
    val error = assertFailsWith<NgaError> {
      fetchHotTopics(testClient(transport), 7, BoardKind.BOARD, now)
    }
    assertTrue(error.text.isNotEmpty())
  }

  @Test
  fun 回复数相同时按最后回复时间再按tid定序() = runTest {
    val transport = RecordingTransport { request ->
      if (pageIndexOf(request.url) == 1) {
        ok(
          pageOf(
            topic(tid = 30, replies = 7, hoursAgo = 2, lastPostHoursAgo = 2),
            topic(tid = 10, replies = 7, hoursAgo = 2, lastPostHoursAgo = 1),
            topic(tid = 20, replies = 7, hoursAgo = 2, lastPostHoursAgo = 2),
          ),
        )
      } else {
        ok(pageOf())
      }
    }

    val result = fetchHotTopics(testClient(transport), 7, BoardKind.BOARD, now)
    assertEquals(listOf(10L, 20L, 30L), result.topics.map { it.tid })
  }
}
