package com.chasel.ng2n.data

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.api.UserPostKind
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.UserAgents
import com.chasel.ng2n.core.net.OK_JSON
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import com.chasel.ng2n.data.board.BoardFavoriteRepository
import com.chasel.ng2n.data.board.HotTopicsRepository
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.data.search.SearchRepository
import com.chasel.ng2n.data.user.UserPostsRepository
import com.chasel.ng2n.data.user.UserProfileRepository
import com.chasel.ng2n.ui.topic.FakeSnapshotSink
import com.chasel.ng2n.ui.topic.TopicFixtures
import com.chasel.ng2n.ui.topic.TopicPageParams
import com.chasel.ng2n.ui.topic.TopicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class RepositoryDispatcherTest {

  private class ThreadProbe {
    @Volatile
    var transportThread: Thread? = null

    @Volatile
    var userAgentThread: Thread? = null

    fun client(body: () -> String = { OK_JSON }): NgaClient = testClient(
      transport = RecordingTransport {
        transportThread = Thread.currentThread()
        ok(body())
      },
      userAgents = UserAgents {
        userAgentThread = Thread.currentThread()
        "ua/for-test"
      },
    )

    fun assertOffThread(caller: Thread) {
      val transport = assertNotNull(transportThread, "这一趟压根没发出请求,判据落空了")
      assertNotSame(caller, transport, "请求发在了调用方线程上(首页那条就是主线程)")
      val userAgent = assertNotNull(userAgentThread, "这一趟没有取 UA,判据落空了")
      assertNotSame(caller, userAgent, "UA 取值发生在调用方线程上 —— 票 35 的死锁就在这一帧")
    }
  }

  @Test
  fun `版块收藏 ensureLoaded —— 首页冷启动那一发不在调用方线程上`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    BoardFavoriteRepository(probe.client()).ensureLoaded("67296151")
    probe.assertOffThread(caller)
  }

  @Test
  fun `版块收藏 reload 同样切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    BoardFavoriteRepository(probe.client()).reload("67296151")
    probe.assertOffThread(caller)
  }

  @Test
  fun `主题列表 ensureFirstPage 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    TopicListRepository(probe.client())
      .ensureFirstPage(
        TopicListRepository.Key(boardId = 650, kind = BoardKind.BOARD, sort = TopicSort.LAST_POST),
      )
    probe.assertOffThread(caller)
  }

  @Test
  fun `热帖 ensureLoaded 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    HotTopicsRepository(probe.client())
      .ensureLoaded(HotTopicsRepository.Key(boardId = 650, kind = BoardKind.BOARD))
    probe.assertOffThread(caller)
  }

  @Test
  fun `某人的主题 ensureFirstPage 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    UserPostsRepository(probe.client())
      .ensureFirstPage(UserPostsRepository.Key(uid = 41417929, kind = UserPostKind.TOPICS))
    probe.assertOffThread(caller)
  }

  @Test
  fun `用户资料 ensureLoaded 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    UserProfileRepository(probe.client()).ensureLoaded(41417929)
    probe.assertOffThread(caller)
  }

  @Test
  fun `搜索 ensureTopicPage 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    SearchRepository(probe.client()).ensureTopicPage(SearchRepository.TopicKey(query = "第六感"))
    probe.assertOffThread(caller)
  }

  @Test
  fun `搜索 ensureBoards 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    SearchRepository(probe.client()).ensureBoards("第六感")
    probe.assertOffThread(caller)
  }

  @Test
  fun `主题详情 loadDetail 切走`() = runTest {
    val caller = Thread.currentThread()
    val probe = ThreadProbe()
    val page = TopicFixtures.pageEnvelope(
      floors = listOf(TopicFixtures.FloorSpec(pid = 0, lou = 0, authorId = 41417929)),
    )
    val repository = TopicRepository(
      client = probe.client { page },
      cachePayloads = FakeSnapshotSink(),
      scope = backgroundScope,
      compute = Dispatchers.Unconfined,
      io = Dispatchers.IO,
    )

    repository.loadDetail(TopicPageParams(tid = 45150945, page = 1))

    probe.assertOffThread(caller)
  }
}
