package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.net.blocked
import com.chasel.ng2n.ui.bbcode.QuoteSegment
import com.chasel.ng2n.ui.bbcode.TextSegment
import com.chasel.ng2n.ui.topic.TopicFixtures.FloorSpec
import com.chasel.ng2n.ui.topic.TopicFixtures.okJson
import com.chasel.ng2n.ui.topic.TopicFixtures.pageEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TopicRepositoryTest {

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
    val params = TopicPageParams(45150945, 2)
    val initial = repository.loadPage(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    assertEquals(1, transport.requests.size)
    assertNull((initial.floors.first().body.segments.first() as QuoteSegment).preview)

    val hydrated = assertNotNull(repository.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS))
    assertEquals(2, transport.requests.size)
    for (floor in hydrated.floors) {
      val preview = assertNotNull((floor.body.segments.first() as QuoteSegment).preview)
      assertEquals("原文", (preview.segments.single() as TextSegment).text.text)
    }
    repository.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    assertEquals(2, transport.requests.size, "后续预览复用已加载页")
  }

  @Test
  fun `原文加载失败保留回复和楼层链接`() = runTest {
    val reply = FloorSpec(21, 21, 1, content = "[b]Reply to [pid=1,45150945,1]Reply[/pid][/b]回复正文")
    val (client, _) = TopicFixtures.client { page, _ ->
      if (page == 2) okJson(pageEnvelope(page = page, floors = listOf(reply))) else blocked()
    }
    val repository = testRepository(client, appScope())
    val params = TopicPageParams(45150945, 2)
    repository.loadPage(params, TopicFixtures.STYLE, TopicFixtures.URLS)
    val model = assertNotNull(repository.loadReplyPreviews(params, TopicFixtures.STYLE, TopicFixtures.URLS))
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

    val model = repository.loadPage(
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

  @Test
  fun `2 分钟内重复取同一页不再打 read_php,refresh 无视新鲜期`() = runTest {
    var served = 0
    val (client, _) = TopicFixtures.client { _, _ ->
      served += 1
      okJson(pageEnvelope(floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())
    val params = TopicPageParams(tid = 45150945, page = 1)

    repository.loadDetail(params, nowMs = 0)
    repository.loadDetail(params, nowMs = 60_000)
    assertEquals(1, served, "还在新鲜期内不该再打一发")

    repository.loadDetail(params, nowMs = 60_000, refresh = true)
    assertEquals(2, served, "显式刷新必须真发请求")

    repository.loadDetail(params, nowMs = 60_000 + TopicRepository.TOPIC_DETAIL_STALE_MS)
    assertEquals(3, served, "过了新鲜期要重打")
  }

  @Test
  fun `过滤视图不进「已加载整页」集合 —— 楼号与分页都是过滤后的口径`() = runTest {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())

    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1))
    repository.loadDetail(TopicPageParams(tid = 45150945, page = 2))
    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1, authorId = 60423359))
    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1, pid = 800000002))
    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1, favCode = "abc"))

    val loaded = repository.loadedPages(tid = 45150945, favCode = null)
    assertEquals(2, loaded.size, "只看此人/只看该楼/带 fav 的都不算整页")

    val withFav = repository.loadedPages(tid = 45150945, favCode = "abc")
    assertEquals(1, withFav.size)
  }

  @Test
  fun `过滤视图的参数会原样进 URL`() = runTest {
    val (client, transport) = TopicFixtures.client { _, _ ->
      okJson(pageEnvelope(floors = defaultFloors, rows = 1))
    }
    val repository = testRepository(client, appScope())

    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1, authorId = 60423359))
    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1, pid = 800000002))

    val urls = transport.requests.map { it.url }
    assertTrue(urls[0].contains("authorid=60423359"), urls[0])
    assertTrue(urls[1].contains("pid=800000002"), urls[1])
  }

  @Test
  fun `filtered 判据`() {
    assertTrue(TopicPageParams(1, 1, authorId = 2).filtered)
    assertTrue(TopicPageParams(1, 1, pid = 2).filtered)
    assertTrue(!TopicPageParams(1, 1, favCode = "x").filtered)
  }

  @Test
  fun `缓存整帖 —— 顺序拉、报进度、跑完清空`() = runTest {
    val progressAtRequest = mutableListOf<Int>()
    lateinit var repositoryRef: () -> TopicRepository
    val (client, transport) = TopicFixtures.client(
      onRequest = { progressAtRequest.add(repositoryRef().cacheDownload.value.done) },
    ) { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val sink = FakeSnapshotSink()
    val repository = testRepository(client, appScope(), sink)
    repositoryRef = { repository }

    val outcome = repository.cacheTopicPages(
      tid = 45150945,
      pages = listOf(1, 2, 3),
      intervalMs = 10,
    )
    advanceUntilIdle()

    assertTrue(outcome is CacheDownloadOutcome.Done)
    assertEquals(3, (outcome as CacheDownloadOutcome.Done).cached)
    assertEquals(3, transport.requests.size)
    assertEquals(3, sink.saved.size, "每页都要交出快照给票 14 存")
    assertEquals(listOf(1, 2, 3), sink.saved.map { it.page })
    assertEquals(listOf(0, 1, 2), progressAtRequest)
    assertNull(repository.cacheDownload.value.tid)
  }

  @Test
  fun `缓存整帖 —— 某一页失败就停手,已存的页保留`() = runTest {
    val (client, _) = TopicFixtures.client { page, _ ->
      if (page >= 2) blocked() else okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val sink = FakeSnapshotSink()
    val repository = testRepository(client, appScope(), sink)

    val outcome = repository.cacheTopicPages(45150945, listOf(1, 2, 3), intervalMs = 10)
    advanceUntilIdle()

    assertTrue(outcome is CacheDownloadOutcome.Failed, "被封那一页要停手")
    assertEquals(1, (outcome as CacheDownloadOutcome.Failed).cached)
    assertEquals(1, sink.saved.size)
    assertNull(repository.cacheDownload.value.tid)
  }

  @Test
  fun `缓存整帖 —— 停止之后不再发请求`() = runTest {
    val (client, transport) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())

    val job = appScope().launchCache(repository, listOf(1, 2, 3, 4, 5))
    advanceUntilIdle()
    repository.cancelCacheDownload()
    advanceUntilIdle()

    val outcome = job.await()
    assertTrue(
      outcome is CacheDownloadOutcome.Cancelled || outcome is CacheDownloadOutcome.Done,
      "停止之后要么是 Cancelled,要么已经跑完 —— 不该是 Failed",
    )
    assertTrue(transport.requests.size <= 5)
    assertNull(repository.cacheDownload.value.tid)
  }

  @Test
  fun `缓存整帖 —— 同一时刻只允许一趟`() = runTest(StandardTestDispatcher()) {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())

    val first = appScope().launchCache(repository, listOf(1, 2, 3), intervalMs = 1_000)
    runCurrent()
    val second = repository.cacheTopicPages(45150945, listOf(1, 2), intervalMs = 10)
    assertEquals(CacheDownloadOutcome.Busy, second)

    repository.cancelCacheDownload()
    advanceUntilIdle()
    assertNotNull(first.await())
  }

  @Test
  fun `空页表直接返回,不占用下载槽`() = runTest {
    val (client, transport) = TopicFixtures.client { _, _ -> okJson(pageEnvelope(floors = defaultFloors)) }
    val repository = testRepository(client, appScope())
    assertEquals(CacheDownloadOutcome.Done(0), repository.cacheTopicPages(1, emptyList()))
    assertEquals(0, transport.requests.size)
  }
}

private fun CoroutineScope.launchCache(
  repository: TopicRepository,
  pages: List<Int>,
  intervalMs: Long = 10,
): Deferred<CacheDownloadOutcome> = async {
  repository.cacheTopicPages(45150945, pages, intervalMs = intervalMs)
}
