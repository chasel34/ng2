package com.chasel.ng2n.data.search

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.net.DEFAULT_NGA_HOST
import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.InMemoryComboCache
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.blocked
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 搜索仓库的翻页/停页/去重/保鲜语义 —— 对照 RN 侧 `store/search.ts` 的三个 query。
 *
 * 判据取的是 RN 版写在 `getNextPageParam` / `staleTime` 上的那几条,
 * 以及票 17a 自己加的「重试要先忘掉 thread.php 的成功组合」。
 */
class SearchRepositoryTest {

  private fun topic(tid: Long, subject: String = "帖 $tid"): String =
    """{"tid":$tid,"subject":"$subject","author":"作者","replies":3,"postdate":1787371200}"""

  /** 一页主题搜索结果。`__ROWS`/`__T__ROWS_PAGE` 决定总页数。 */
  private fun page(vararg rows: String, totalRows: Int = 100): String {
    val entries = rows.withIndex().joinToString(",") { (index, row) -> "\"$index\":$row" }
    return """{"data":{"__T":{$entries},"__F":{},"__ROWS":$totalRows,"__T__ROWS_PAGE":35}}"""
  }

  private fun pageIndexOf(url: String): Int =
    URI(url).rawQuery.orEmpty().split("&")
      .firstOrNull { it.startsWith("page=") }
      ?.removePrefix("page=")
      ?.toIntOrNull()
      ?: 1

  private val query = SearchRepository.TopicKey(query = "第六感")

  @Test
  fun `翻两页后按 tid 去重拼在一起`() = runTest {
    val transport = RecordingTransport { request ->
      when (pageIndexOf(request.url)) {
        1 -> ok(page(topic(1), topic(2)))
        // 置顶/镜像行会在下一页再回来一次
        2 -> ok(page(topic(2), topic(3)))
        else -> ok(page())
      }
    }
    val repo = SearchRepository(testClient(transport))

    repo.ensureTopicPage(query)
    repo.loadNextTopicPage(query)

    assertEquals(listOf(1L, 2L, 3L), repo.topicStateOf(query).topics.map { it.tid })
  }

  @Test
  fun `ensureTopicPage 幂等 —— 返回这一屏不重打接口`() = runTest {
    val transport = RecordingTransport { ok(page(topic(1))) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureTopicPage(query)
    repo.ensureTopicPage(query)

    assertEquals(1, transport.requests.size)
  }

  @Test
  fun `空页就是到底了 —— 不再往下翻`() = runTest {
    val transport = RecordingTransport { request ->
      if (pageIndexOf(request.url) == 1) ok(page(topic(1))) else ok(page())
    }
    val repo = SearchRepository(testClient(transport))

    repo.ensureTopicPage(query)
    repo.loadNextTopicPage(query)
    assertFalse(repo.topicStateOf(query).hasNextPage)

    val before = transport.requests.size
    repo.loadNextTopicPage(query)
    assertEquals(before, transport.requests.size, "到底之后不该再发请求")
  }

  @Test
  fun `翻过总页数就停 —— 服务端的 ROWS 说了算`() = runTest {
    // totalRows = 35 ⇒ 只有 1 页
    val transport = RecordingTransport { ok(page(topic(1), totalRows = 35)) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureTopicPage(query)

    assertFalse(repo.topicStateOf(query).hasNextPage)
  }

  @Test
  fun `换一种搜法就是另一份数据 —— 范围与含正文进 key`() = runTest {
    val transport = RecordingTransport { request ->
      if (request.url.contains("content=1")) ok(page(topic(9))) else ok(page(topic(1)))
    }
    val repo = SearchRepository(testClient(transport))

    val withContent = query.copy(content = true)
    repo.ensureTopicPage(query)
    repo.ensureTopicPage(withContent)

    assertEquals(listOf(1L), repo.topicStateOf(query).topics.map { it.tid })
    assertEquals(listOf(9L), repo.topicStateOf(withContent).topics.map { it.tid })
  }

  @Test
  fun `限定版块时普通版块传 fid 合集传 stid`() = runTest {
    val transport = RecordingTransport { ok(page(topic(1))) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureTopicPage(query.copy(boardId = 7))
    repo.ensureTopicPage(query.copy(boardId = 42, kind = BoardKind.COLLECTION))

    assertTrue(transport.requests[0].url.contains("fid=7"))
    assertTrue(transport.requests[1].url.contains("stid=42"))
    assertFalse(transport.requests[1].url.contains("fid=42"))
  }

  @Test
  fun `拉失败落在 error 上 —— 不把它说成空结果`() = runTest {
    val repo = SearchRepository(testClient(RecordingTransport { blocked() }))

    repo.ensureTopicPage(query)

    val state = repo.topicStateOf(query)
    assertNotNull(state.error)
    assertTrue(state.topics.isEmpty())
    assertFalse(state.loading)
  }

  /**
   * 「重试」要先忘掉 `thread.php` 上次试通的组合 —— 与版块列表同一条理由
   * (2026-08-13「版块全空」排查),而且这两条路共用同一条 comboCache 记录。
   */
  @Test
  fun `重试会忘掉 thread点php 的成功组合`() = runTest {
    val transport = RecordingTransport { ok(page(topic(1))) }
    val comboCache = InMemoryComboCache()
    // 手动种一条「上次试通的组合」——用户按重试的时候,恰恰是这条记录不可信的时候
    comboCache.remember(
      "thread.php",
      FetchCombo(format = ResponseFormat.JSON_VERBOSE, host = DEFAULT_NGA_HOST),
    )
    val repo = SearchRepository(testClient(transport, comboCache = comboCache))

    repo.retryTopics(query)

    assertNull(comboCache.get("thread.php"), "重试要让这半个 app 从默认顺序重新试探")
    assertEquals(listOf(1L), repo.topicStateOf(query).topics.map { it.tid })
  }

  // ------------------------------------------------------------ 版块搜索

  @Test
  fun `版块搜索一次给全量 没有分页`() = runTest {
    val body = """
      {"data":{"0":{"fid":7,"stid":0,"name":"网事杂谈","descrip":"闲聊",
      "parent":{"fid":-7,"name":"大区"}},"1":{"fid":459,"stid":0,"name":"魔兽世界"}}}
    """.trimIndent()
    val transport = RecordingTransport { ok(body) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureBoards("杂谈")

    val state = repo.boardStateOf("杂谈")
    assertEquals(listOf("网事杂谈", "魔兽世界"), state.items.map { it.board.name })
    assertEquals("大区", state.items[0].parentName)
    assertTrue(state.loaded)
  }

  @Test
  fun `没搜到版块是空列表而不是错误 —— 「没找到」在假错误白名单里`() = runTest {
    val transport = RecordingTransport { ok("""{"error":["没找到符合条件的版面"]}""") }
    val repo = SearchRepository(testClient(transport))

    repo.ensureBoards("不存在的版")

    val state = repo.boardStateOf("不存在的版")
    assertTrue(state.items.isEmpty())
    assertNull(state.error)
    assertTrue(state.loaded)
  }

  @Test
  fun `版块搜索的关键词走 GBK —— API 文档 §1点2`() = runTest {
    val transport = RecordingTransport { ok("""{"data":{"__MESSAGE":"x"}}""") }
    val repo = SearchRepository(testClient(transport))

    repo.ensureBoards("杂谈")

    // GBK 的「杂谈」= D4 D3 CC B8;UTF-8 会是 %E6%9D%82%E8%B0%88
    val url = transport.requests.single().url
    assertTrue(url.contains("%D4%D3%CC%B8", ignoreCase = true), url)
  }

  // ------------------------------------------------------------ 用户搜索

  private val profileBody = """
    {"data":{"0":{"uid":42,"username":"张三","postnum":128}}}
  """.trimIndent()

  @Test
  fun `纯数字按 uid 查 其余按用户名查`() = runTest {
    val transport = RecordingTransport { ok(profileBody) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureUser("42")
    repo.ensureUser("张三")

    assertTrue(transport.requests[0].url.contains("uid=42"))
    assertTrue(transport.requests[1].url.contains("username="))
  }

  @Test
  fun `五分钟内重搜同一个人不再打 ucp —— RN 侧 staleTime`() = runTest {
    val transport = RecordingTransport { ok(profileBody) }
    val repo = SearchRepository(testClient(transport))

    // 基准时刻取一个非 0 值:仓库把 `fetchedAt == 0` 当成「还没取过」
    val t0 = 1_787_371_200_000L
    repo.ensureUser("42", now = t0)
    repo.ensureUser("42", now = t0 + 4 * 60 * 1000)
    assertEquals(1, transport.requests.size)

    repo.ensureUser("42", now = t0 + 6 * 60 * 1000)
    assertEquals(2, transport.requests.size)
  }

  @Test
  fun `空输入不发请求`() = runTest {
    val transport = RecordingTransport { ok(profileBody) }
    val repo = SearchRepository(testClient(transport))

    repo.ensureUser("   ")

    assertTrue(transport.requests.isEmpty())
  }
}
