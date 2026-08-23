package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.createFilterRule
import com.chasel.ng2n.ui.topic.TopicFixtures.FloorSpec
import com.chasel.ng2n.ui.topic.TopicFixtures.okJson
import com.chasel.ng2n.ui.topic.TopicFixtures.pageEnvelope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TopicViewModel] 的行为。全套依赖走内存假件(见 [FakeTopicDeps]),纯 JVM。
 *
 * `viewModelScope` 挂在 `Dispatchers.Main` 上,所以要 `Dispatchers.setMain`
 * 把它换成测试调度器 —— 不换的话 Android 的 Main looper 在 JVM 上不存在。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()

  /**
   * 「全 app 一个的 IO scope」的替身。
   *
   * 不用 `runTest` 的 `backgroundScope`:那个 scope 里的协程不保证被
   * `advanceUntilIdle()` 推到底(实测阅读进度的落盘协程一直不跑),
   * 自己建一个挂在测试调度器上的最稳。
   */
  private lateinit var appScope: CoroutineScope

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    appScope = CoroutineScope(dispatcher + SupervisorJob())
  }

  @After
  fun tearDown() {
    // 先 clear 再 resetMain:viewModelScope 里那条 settings.collect 永不结束,
    // 不清掉的话它会在下一个用例里被已经关掉的 Main 调度器唤醒,抛出去污染那一条
    store.clear()
    appScope.cancel()
    Dispatchers.resetMain()
  }

  /** 建一个由 [store] 托管的 ViewModel(单测里唯一造 VM 的口子)。 */
  private fun viewModel(key: TopicKey, fakes: FakeTopicDeps): TopicViewModel {
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TopicViewModel(key, fakes.deps, dispatcher) as T
    }
    return ViewModelProvider(store, factory)[key.toString(), TopicViewModel::class.java]
  }

  private val floors = listOf(
    FloorSpec(pid = 0, lou = 0, authorId = 41417929, authorName = "楼主", content = "主楼"),
    FloorSpec(pid = 800000001, lou = 1, authorId = 60423359, authorName = "甲", content = "一楼"),
    FloorSpec(pid = 800000002, lou = 2, authorId = 66807492, authorName = "乙", content = "二楼含关键词"),
  )

  private fun envelope(page: Int, rows: Long = 47) =
    pageEnvelope(page = page, floors = floors, rows = rows)

  @Test
  fun `进场拉第一页 并顺手预取下一页(上一页不预取)`() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)

    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertEquals(1, vm.page)
    assertEquals(3, vm.totalPages)
    assertNotNull(vm.currentModel)
    assertEquals(3, vm.currentModel!!.floors.size)
    // 第 1 页 + 预取的第 2 页,一共两发;**没有**第 0 页
    val pages = transport.requests.map { it.url.substringAfter("page=").substringBefore("&") }
    assertEquals(listOf("1", "2"), pages)
  }

  @Test
  fun `翻页三入口收敛到同一个页码规则 跳页不夹逼`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.goToPage(2)
    assertEquals(2, vm.page)
    // 页码条/横滑一律夹逼
    vm.goToPage(99)
    assertEquals(3, vm.page)
    vm.goToPage(0)
    assertEquals(1, vm.page)

    // 跳页超范围不动页码,只给一句提示
    vm.jumpTo("999")
    assertEquals(1, vm.page)
    assertEquals("请输入 1 – 3 之间的页码", vm.toast.value?.text)
    vm.consumeToast()

    vm.jumpTo("3")
    assertEquals(3, vm.page)
  }

  @Test
  fun `横滑松手先切页码条高亮 —— 但不换数据`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.setPageInFlight(2)
    assertEquals(2, vm.pageInFlight)
    assertEquals(1, vm.page, "定向那一刻不许换数据")

    vm.goToPage(2)
    assertEquals(2, vm.page)
    assertNull(vm.pageInFlight, "page 追上来就清账")
  }

  @Test
  fun `阅读进度只前进 且过滤视图期间一律不记`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.reportVisibleFloor(5)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(5, fakes.historyDao.find(45150945)?.lastFloor)

    // 往回翻不该把进度拉回去
    vm.reportVisibleFloor(2)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(5, fakes.historyDao.find(45150945)?.lastFloor, "只前进")

    vm.reportVisibleFloor(9)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(9, fakes.historyDao.find(45150945)?.lastFloor)

    // 只看此人期间楼号是过滤后的口径,写进去会串档
    vm.enterOnlyUser(vm.currentModel!!.floors[1])
    advanceUntilIdle()
    assertTrue(vm.progressPaused)
    vm.reportVisibleFloor(1)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(9, fakes.historyDao.find(45150945)?.lastFloor, "过滤视图不许改进度")
  }

  @Test
  fun `只看此人 进出都清页并回到进入前那一页`() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    vm.goToPage(3)
    advanceUntilIdle()

    vm.enterOnlyUser(vm.currentModel!!.floors[1])
    advanceUntilIdle()
    assertEquals(1, vm.page, "进过滤视图从第 1 页起")
    assertEquals(60423359L, vm.onlyUser?.uid)
    assertTrue(transport.requests.any { it.url.contains("authorid=60423359") })

    vm.exitOnlyUser()
    advanceUntilIdle()
    assertNull(vm.onlyUser)
    assertEquals(3, vm.page, "退出过滤回到进入前那一页")
  }

  @Test
  fun `匿名楼层不能只看此人`() = runTest(dispatcher) {
    val anonymous = listOf(
      FloorSpec(pid = 0, lou = 0, authorId = -1, authorName = "#anony_0123456789abcdef0123456789abcdef"),
    )
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = anonymous, rows = 1))
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.enterOnlyUser(vm.currentModel!!.floors[0])
    assertNull(vm.onlyUser)
    assertEquals("匿名用户无法只看", vm.toast.value?.text)
  }

  @Test
  fun `屏蔽规则在数据层一次算完 楼层卡只查表 点开就不再折`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    // 先落一条关键词规则,再开屏
    fakes.settingsStore.updateFilterRules {
      listOf(
        createFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "关键词"), 0).toStoredRule(),
      )
    }
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val hit = vm.currentModel!!.floors.single { it.lou == 2L }
    assertNotNull(vm.blockedRuleOf(hit), "命中关键词的楼要折起来")
    assertNull(vm.blockedRuleOf(vm.currentModel!!.floors[0]))

    vm.expandFloor(hit.pid)
    assertNull(vm.blockedRuleOf(hit), "手动点开之后这次停留里不再折")
  }

  @Test
  fun `屏蔽此人 落一条本地规则并给撤销`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val target = vm.currentModel!!.floors.single { it.lou == 1L }
    vm.blockAuthor(target)
    advanceUntilIdle()

    assertNotNull(vm.blockedRuleOf(target), "加完规则这一楼当场折起来")
    val snack = assertNotNull(vm.snackbar.value)
    assertEquals("已屏蔽 甲,其发言将折叠", snack.text)
    assertEquals("撤销", snack.actionLabel)
    assertEquals(
      listOf("local:user:甲"),
      fakes.settingsStore.localFilterRules.first().map { it.id },
      "规则要真的落到盘上",
    )

    snack.action!!.invoke()
    advanceUntilIdle()
    assertNull(vm.blockedRuleOf(target), "撤销之后不再折")
    // 票 25:现场是「提示条消失了、规则还在盘上」,所以这里必须查盘而不是只查折叠状态
    assertEquals(
      emptyList(),
      fakes.settingsStore.localFilterRules.first(),
      "撤销要把刚加的那条从 DataStore 里删掉",
    )
  }

  @Test
  fun `票 25 撤销挂在 app scope 上 这一屏退场了也照样删得掉`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.blockAuthor(vm.currentModel!!.floors.single { it.lou == 1L })
    advanceUntilIdle()
    val snack = assertNotNull(vm.snackbar.value)
    assertEquals(1, fakes.settingsStore.localFilterRules.first().size)

    // 提示条本来就设计成「发起它的页面退场之后还活着」:退场之后点撤销仍要生效。
    // 挂 viewModelScope 时这里是一句 no-op —— 往已取消的 scope 上 launch 不抛也不跑
    store.clear()
    snack.action!!.invoke()
    advanceUntilIdle()
    assertEquals(
      emptyList(),
      fakes.settingsStore.localFilterRules.first(),
      "撤销不该随 ViewModel 一起被取消",
    )
  }

  @Test
  fun `自动加载下一页 只认用户亲手滚出来的到底`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.onReachedEnd()
    assertEquals(1, vm.page, "没滚过就到底 = 程序化滚动,不翻页")

    vm.userScrolled = true
    vm.onReachedEnd()
    assertEquals(2, vm.page)
    advanceUntilIdle()
    assertTrue(!vm.userScrolled, "翻页之后重新记账")
  }

  @Test
  fun `末页到底不再往前翻`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page, rows = 3)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertEquals(1, vm.totalPages)
    vm.userScrolled = true
    vm.onReachedEnd()
    assertEquals(1, vm.page)
  }

  @Test
  fun `带楼号进场 —— 按每页 20 楼估页码,数据到位后给出滚动目标`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(
        pageEnvelope(
          page = page,
          floors = listOf(
            FloorSpec(pid = 800000020, lou = 20, authorId = 1),
            FloorSpec(pid = 800000021, lou = 21, authorId = 1),
            FloorSpec(pid = 800000022, lou = 22, authorId = 1),
          ),
          rows = 47,
        ),
      )
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945, floor = 21), fakes)
    // 21 楼 / 每页 20 → 第 2 页
    assertEquals(2, vm.page)

    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val target = assertNotNull(vm.scrollTarget)
    assertEquals(2, target.page)
    assertEquals(1, target.index, "21 楼是这一页的第 2 条")
    vm.consumeScrollTarget()
    assertNull(vm.scrollTarget)
  }

  @Test
  fun `上次读到浮条 —— 没读过不打扰,翻页即收走,消失是单向的`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)

    val first = viewModel(TopicKey(tid = 45150945), fakes)
    first.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertNull(first.resumeFloor, "主楼都没读过就不打扰")

    // 攒一条阅读进度,再开一次同一个主题
    first.reportVisibleFloor(7)
    first.flushReadFloor()
    advanceUntilIdle()

    val second = viewModel(TopicKey(tid = 45150945, page = 1), fakes)
    second.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertEquals(7L, second.resumeFloor)
    assertTrue(second.resumeVisible)

    second.dismissResume()
    assertTrue(!second.resumeVisible)
    // 「消失」= 「我知道了」,翻回来也不该再冒出来
    second.goToPage(2)
    advanceUntilIdle()
    second.goToPage(1)
    advanceUntilIdle()
    assertTrue(!second.resumeVisible)
  }

  @Test
  fun `数据来源提示条 —— 原生直出不出条,重试原生会清掉组合缓存`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertEquals(com.chasel.ng2n.core.api.TopicSource.NATIVE, vm.source)
    vm.dismissSourceNotice()
    assertTrue(vm.sourceNoticeDismissed)
    vm.retryNative()
    advanceUntilIdle()
    assertTrue(!vm.sourceNoticeDismissed, "重试时把提示条放回来,不然重试失败了用户看不出还在兜底")
  }

  @Test
  fun `赞踩 —— 未登录先引导登录,登录后乐观更新再按服务端 delta 校正`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, uri ->
      if (uri.rawQuery.orEmpty().contains("topic_recommend")) {
        okJson("""{"data":{"0":"ok","1":1}}""")
      } else {
        okJson(envelope(page))
      }
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val floor = vm.currentModel!!.floors[1]
    var prompted = false
    vm.recommend(floor, com.chasel.ng2n.core.api.RecommendAction.LIKE) { prompted = true }
    advanceUntilIdle()
    assertTrue(prompted, "游客点赞要先引导登录")
    assertNull(vm.markOf(floor))

    fakes.credentials.signIn("10000001")
    vm.recommend(floor, com.chasel.ng2n.core.api.RecommendAction.LIKE) { prompted = true }
    advanceUntilIdle()
    val mark = assertNotNull(vm.markOf(floor))
    assertEquals(com.chasel.ng2n.core.api.RecommendState.LIKED, mark.state)
    assertEquals(1L, mark.scoreDelta)
  }

  @Test
  fun `缓存本页 —— 已经在缓存里就只确认一句,不再打 read_php`() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    val before = transport.requests.size

    // 浏览时的自动缓存(延后 320ms)已经把第 1 页存下了
    advanceUntilIdle()
    fakes.topicCache.savePage(fakes.snapshotSink.saved.first().toCachedSnapshot())
    advanceUntilIdle()

    vm.cacheCurrentPage { }
    advanceUntilIdle()
    assertEquals("本页已缓存,可离线阅读", vm.snackbar.value?.text)
    assertEquals(before, transport.requests.size, "已经存过就不该再打一发")
  }

  @Test
  fun `一页都没加载出来时缓存动作只给提示`() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { _, _ -> com.chasel.ng2n.core.net.blocked() }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertTrue(vm.pages[1] is PageState.Failed)
    vm.cacheWholeTopic { }
    assertEquals("这一页还没加载出来", vm.toast.value?.text)
  }
}

/** 快照 core → 存储层(生产里由 `TopicCachePayloadReader` 搬,单测手工搬一次)。 */
private fun com.chasel.ng2n.core.api.TopicPageSnapshot.toCachedSnapshot() =
  com.chasel.ng2n.data.cache.CachedPageSnapshot(
    tid = tid,
    page = page,
    subject = subject,
    boardName = boardName,
    favCode = favCode,
    floors = floors,
    totalPages = totalPages,
    payload = payload,
  )
