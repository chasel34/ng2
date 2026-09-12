package com.chasel.ng2n.ui.topic

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

/**
 * [TopicRepository] 的页转换与「缓存整帖」。
 *
 * 假的只有传输层(见 [TopicFixtures]),所以「一页字节 → 渲染成品」这条链是端到端的:
 * 票 04 的清洗与信封 → 票 07 的 `parseTopicDetail` → 本票的 [TopicPageBuilder]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicRepositoryTest {

  /**
   * 「全 app 一个的 IO scope」的替身 —— 不用 `backgroundScope`,理由同
   * [TopicViewModelTest]:那里面的协程不保证被 `advanceUntilIdle()` 推到底。
   */
  private fun TestScope.appScope(): CoroutineScope =
    CoroutineScope(coroutineContext.job + StandardTestDispatcher(testScheduler))

  private val defaultFloors = listOf(
    FloorSpec(pid = 0, lou = 0, authorId = 41417929, authorName = "楼主", content = "主楼[b]正文[/b]"),
    FloorSpec(pid = 800000001, lou = 1, authorId = 60423359, content = "一楼"),
    FloorSpec(pid = 800000002, lou = 2, authorId = 66807492, content = "二楼"),
  )

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
    // 47 楼 / 每页 20 = 3 页
    assertEquals(3, model.totalPages)
    assertEquals(3, model.floors.size)
    assertEquals(listOf(0L, 1L, 2L), model.floors.map { it.lou })
    // 主楼的赞踩 pid 必须是 0(API 文档 §6)
    assertEquals(0L, model.floors[0].recommendPid)
    assertEquals(800000001L, model.floors[1].recommendPid)
    assertTrue(model.floors[0].isStarter)
    assertEquals("楼主", model.floors[0].displayName)
    // 正文已经建成成品(不是 BBCode 原文)
    assertTrue(model.floors[0].body.segments.isNotEmpty())
    assertEquals("https://img.nga.cn/attachments", model.attachBase)
    // 只发了一次 read.php
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
    // fav 码不同也是另一份数据
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
    // 进度是一页一页往前走的(在每一发请求进来的那一刻读):第 1 页时 0、第 2 页时 1……
    assertEquals(listOf(0, 1, 2), progressAtRequest)
    // 跑完必须回到空闲,不然进度条一直挂在那儿
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
    // 第一页立刻发(「缓存本页」按下去就该有反应),之后每页之间隔一会儿
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
    // 让第一趟真正跑起来(拿到锁),再开第二趟
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
