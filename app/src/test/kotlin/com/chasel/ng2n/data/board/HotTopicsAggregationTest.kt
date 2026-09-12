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

/**
 * 24 小时热帖(CONTEXT.md「热帖」)的端到端聚合:拉页(票 07)+ 窗口过滤排序(票 10)。
 *
 * 判据全在 `core/local/HotTopics.kt`(那儿有票 10 的金样本),这里钉的是**首页/版块面
 * 真正调用的那一条路**:并发拉 5 页 → 跨页去重 → 剔掉合集/镜像/外链 → 按发帖时间
 * 过窗口 → 回复数降序。
 */
class HotTopicsAggregationTest {

  /** 秒级 unix 时间戳的「现在」。 */
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
    // 置顶主题每页都会再回来一次,按 tid 去重
    assertEquals(listOf(2L, 3L, 1L), result.topics.map { it.tid })
  }

  @Test
  fun 窗口按发帖时间_老坟被顶起来也不进榜() = runTest {
    val transport = RecordingTransport { request ->
      if (pageIndexOf(request.url) == 1) {
        ok(
          pageOf(
            // 发帖在窗口内
            topic(tid = 1, replies = 3, hoursAgo = 5),
            // 十年老坟,刚被顶起来:lastpost 很新,但 postdate 在窗口外
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
            // type 含 0x8000 = 合集
            topic(tid = 2, replies = 100, hoursAgo = 1, extra = ""","type":32768"""),
            // 外链活动帖
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
    // 最后回复更新的排前;并列时 tid 升序
    assertEquals(listOf(10L, 20L, 30L), result.topics.map { it.tid })
  }
}
