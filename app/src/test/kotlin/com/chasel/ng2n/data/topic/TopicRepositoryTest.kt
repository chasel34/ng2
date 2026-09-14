package com.chasel.ng2n.data.topic

import com.chasel.ng2n.ui.topic.TopicFixtures
import com.chasel.ng2n.ui.topic.FakeSnapshotSink
import com.chasel.ng2n.ui.topic.testRepository
import kotlin.test.assertSame
import com.chasel.ng2n.core.net.blocked
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
  fun `按 pid 读取保留定位楼层与 fav 参数且重复读取命中缓存`() = runTest {
    val target = FloorSpec(800000099, 99, 42, content = "目标楼层")
    val (client, transport) = TopicFixtures.client { _, uri ->
      assertTrue(uri.rawQuery.contains("pid=800000099"))
      assertTrue(uri.rawQuery.contains("fav=secret"))
      okJson(pageEnvelope(page = 5, floors = listOf(target), rows = 100))
    }
    val repository = testRepository(client, appScope())
    val params = TopicPageParams(45150945, 1, favCode = "secret", pid = target.pid)

    val detail = repository.loadDetail(params)
    assertEquals(target.pid, detail.floors.single().pid)
    assertEquals(99L, detail.floors.single().lou)
    assertEquals("目标楼层", detail.floors.single().content)
    assertSame(detail, repository.loadDetail(params))
    assertEquals(1, transport.requests.size)
    assertTrue(repository.loadedPages(params.tid, params.favCode).isEmpty())
  }

  @Test
  fun `已加载页按页码排序且与读取缓存共享原始对象`() = runTest {
    val (client, transport) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())
    val second = repository.loadDetail(TopicPageParams(45150945, 2))
    val first = repository.loadDetail(TopicPageParams(45150945, 1))

    val pages = repository.loadedPages(45150945, null)
    assertEquals(listOf(1, 2), pages.map { it.page })
    assertSame(first, pages[0])
    assertSame(second, pages[1])
    assertSame(second, repository.loadDetail(TopicPageParams(45150945, 2)))
    assertEquals(2, transport.requests.size)
  }

  @Test
  fun `热门回复随主楼读取并保留作者正文和楼层坐标`() = runTest {
    val hot = FloorSpec(800000099, 99, 42, authorName = "热门作者", content = "热门正文", score = 77)
    val (client, transport) = TopicFixtures.client { _, _ ->
      okJson(pageEnvelope(floors = defaultFloors, hotReplies = listOf(hot)))
    }
    val repository = testRepository(client, appScope())
    val params = TopicPageParams(45150945, 1)
    val detail = repository.loadDetail(params)
    val floor = detail.hotReplies.single()

    assertEquals(hot.pid, floor.pid)
    assertEquals(hot.lou, floor.lou)
    assertEquals(hot.content, floor.content)
    assertEquals(hot.score, floor.score)
    assertEquals(hot.authorName, detail.users[floor.authorKey]?.name)
    assertSame(detail, repository.loadDetail(params))
    assertSame(detail, repository.loadedPages(params.tid, null).single())
    assertEquals(1, transport.requests.size)
  }

  @Test
  fun `缓存最多保留四十条且读取命中不改变淘汰顺序`() = runTest {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val repository = testRepository(client, appScope())
    for (page in 1..40) repository.loadDetail(TopicPageParams(45150945, page), nowMs = 0)
    repository.loadDetail(TopicPageParams(45150945, 1), nowMs = 1)
    repository.loadDetail(TopicPageParams(45150945, 41), nowMs = 1)

    assertNull(repository.cachedDetail(TopicPageParams(45150945, 1), nowMs = 1))
    assertNotNull(repository.cachedDetail(TopicPageParams(45150945, 2), nowMs = 1))
    assertEquals((2..41).toList(), repository.loadedPages(45150945, null).map { it.page })
  }

  @Test
  fun `前台读取延迟保存快照且过滤视图不写整页缓存`() = runTest {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = defaultFloors))
    }
    val sink = FakeSnapshotSink()
    val repository = testRepository(client, appScope(), sink)
    repository.loadDetail(TopicPageParams(45150945, 1))
    repository.loadDetail(TopicPageParams(45150945, 1, pid = 800000002))
    repository.loadDetail(TopicPageParams(45150945, 1, authorId = 60423359))
    assertTrue(sink.saved.isEmpty())
    advanceUntilIdle()
    assertEquals(listOf(1), sink.saved.map { it.page })
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
    assertEquals(3, sink.saved.size, "每页都保存快照")
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
