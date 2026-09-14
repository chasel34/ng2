package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.data.topic.TopicPageParams
import com.chasel.ng2n.core.net.blocked
import com.chasel.ng2n.ui.bbcode.QuoteSegment
import com.chasel.ng2n.ui.bbcode.TextSegment
import com.chasel.ng2n.ui.topic.TopicFixtures.FloorSpec
import com.chasel.ng2n.ui.topic.TopicFixtures.okJson
import com.chasel.ng2n.ui.topic.TopicFixtures.pageEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TopicPageLoaderTest {

  private fun TestScope.appScope(): CoroutineScope =
    CoroutineScope(coroutineContext.job + StandardTestDispatcher(testScheduler))

  private val defaultFloors = listOf(
    FloorSpec(pid = 0, lou = 0, authorId = 41417929, authorName = "楼主", content = "主楼[b]正文[/b]"),
    FloorSpec(pid = 800000001, lou = 1, authorId = 60423359, content = "一楼"),
    FloorSpec(pid = 800000002, lou = 2, authorId = 66807492, content = "二楼"),
  )

  @Test
  fun `原文预览在主页面之后加载 同页引用合并请求 不递归加载上游`() = runTest {
    val replies = listOf(21L, 22L).map {
      FloorSpec(it, it, 1, content = "[b]Reply to [pid=1,45150945,1]Reply[/pid][/b]回复$it")
    }
    val original = FloorSpec(1, 1, 1, content = "[b]Reply to [pid=99,45150945,9]Reply[/pid][/b]原文")
    val (client, transport) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = if (page == 2) replies else listOf(original)))
    }
    val repository = testRepository(client, appScope())
    val loader = TopicPageLoader(repository, kotlinx.coroutines.Dispatchers.Unconfined)
    val params = TopicPageParams(45150945, 2)
    val initial = loader.loadPage(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    assertEquals(1, transport.requests.size)
    assertNull((initial.floors.first().body.segments.first() as QuoteSegment).preview)

    val hydrated = assertNotNull(loader.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS))
    assertEquals(2, transport.requests.size)
    for (floor in hydrated.floors) {
      val preview = assertNotNull((floor.body.segments.first() as QuoteSegment).preview)
      assertEquals("原文", (preview.segments.single() as TextSegment).text.text)
    }
    loader.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    assertEquals(2, transport.requests.size, "后续预览复用已加载页")
  }

  @Test
  fun `原文加载失败保留回复和楼层链接`() = runTest {
    val reply = FloorSpec(21, 21, 1, content = "[b]Reply to [pid=1,45150945,1]Reply[/pid][/b]回复正文")
    val (client, _) = TopicFixtures.client { page, _ ->
      if (page == 2) okJson(pageEnvelope(page = page, floors = listOf(reply))) else blocked()
    }
    val repository = testRepository(client, appScope())
    val loader = TopicPageLoader(repository, kotlinx.coroutines.Dispatchers.Unconfined)
    val params = TopicPageParams(45150945, 2)
    loader.loadPage(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    val model = assertNotNull(loader.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS))
    val header = model.floors.single().body.segments.first() as QuoteSegment
    assertEquals(1L, header.chain?.pid)
    assertNull(header.preview)
    assertEquals("回复正文", (model.floors.single().body.segments.last() as TextSegment).text.text)
  }

  @Test
  fun `页转换 —— 字节到渲染成品`() = runTest {
    val (client, transport) = TopicFixtures.client { _, _ ->
      okJson(pageEnvelope(floors = defaultFloors, rows = 47, rowsPerPage = 20))
    }
    val repository = testRepository(client, appScope())
    val loader = TopicPageLoader(repository, kotlinx.coroutines.Dispatchers.Unconfined)

    val model = loader.loadPage(
      params = TopicPageParams(tid = 45150945, page = 1),
      style = TopicFixtures.STYLE,
      urls = TopicFixtures.URLS,
    )

    assertEquals(1, model.page)
    assertEquals("测试主题", model.subject)
    assertEquals("网事杂谈", model.boardName)
    assertEquals(47, model.totalRows)
    assertEquals(3, model.totalPages)
    assertEquals(3, model.floors.size)
    assertEquals(listOf(0L, 1L, 2L), model.floors.map { it.lou })
    assertEquals(0L, model.floors[0].recommendPid)
    assertEquals(800000001L, model.floors[1].recommendPid)
    assertTrue(model.floors[0].isStarter)
    assertEquals("楼主", model.floors[0].displayName)
    assertTrue(model.floors[0].body.segments.isNotEmpty())
    assertEquals("https://img.nga.cn/attachments", model.attachBase)
    assertEquals(1, transport.requests.size)
    assertTrue(transport.requests.single().url.contains("read.php"))
  }

}
