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

/**
 * 票 35 的第二半:**仓库入口自己把网络切到 IO,不靠调用方**。
 *
 * 现场是首页那两个 `LaunchedEffect` —— 组合的上下文是 `AndroidUiDispatcher`(主线程),
 * 仓库里又没有 `withContext(Dispatchers.IO)`,于是 `NgaClient.execute` 的前半段
 * (含 `UserAgents.get`)就在主线程上跑,一头撞进 UA 那把锁里(线程栈见票 35)。
 * 死锁修掉之后这仍然是个独立缺陷:首页冷启动在主线程上跑请求链,白白卡首帧。
 *
 * 判据取的是「**发请求的线程不是调用仓库的那条线程**」:`runTest` 的协程跑在测试调度器的
 * 线程上,`withContext(Dispatchers.IO)` 之后必然换线程。UA 取值那一发单独再断一次 ——
 * 它是死锁现场里主线程栈的最后一帧,必须跟着一起离开调用方线程。
 */
class RepositoryDispatcherTest {

  /** 记下「请求真正发出去时」和「UA 真正取值时」各自在哪条线程上。 */
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

  /**
   * 票 37:详情页是同一类缺陷的最后一处 —— [TopicRepository.loadDetail] 里的
   * `fetchTopicDetail` 原本跟着调用方走,而 `TopicViewModel` / `ChainViewModel` 一律
   * `viewModelScope.launch`(= `Main.immediate`),于是每翻一页请求链前半段都在主线程上。
   *
   * 这里给的 `io` 是真的 [Dispatchers.IO](不是测试调度器),判据才有意义:
   * **发请求的线程不是调用方线程**。
   */
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
