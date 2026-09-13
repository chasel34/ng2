package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.ChainKey
import com.chasel.ng2n.ui.bbcode.QuoteSegment
import com.chasel.ng2n.ui.bbcode.TextSegment
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.createFilterRule
import com.chasel.ng2n.data.bookmarks.BookmarkDraft
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

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()

  private lateinit var appScope: CoroutineScope

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    appScope = CoroutineScope(dispatcher + SupervisorJob())
  }

  @After
  fun tearDown() {
    store.clear()
    appScope.cancel()
    Dispatchers.resetMain()
  }

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
  fun `第三页引用主楼显示两层回复链 打开链后自动读取第一页`() = runTest(dispatcher) {
    val starter = FloorSpec(0, 0, 1, content = "主楼完整正文")
    val reply = FloorSpec(45, 45, 2, content = "[quote][tid=45150945]Topic[/tid]主楼摘录[/quote]第45楼回复")
    val (client, transport) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = listOf(if (page == 1) starter else reply), rows = 46))
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945, page = 3), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertEquals(2, vm.chainDepths[45])
    assertEquals(1, transport.requests.size)

    val chain = ChainViewModel(ChainKey(tid = 45150945, pid = 45), fakes.deps, dispatcher)
    store.put("chain", chain)
    chain.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertEquals(listOf(0L, 45L), chain.chain.map { it.pid })
    assertTrue(chain.chain.all { it.loaded })
    assertEquals("主楼完整正文", (chain.entries.getValue(0).body.segments.single() as TextSegment).text.text)
    assertEquals("第45楼回复", (chain.entries.getValue(45).body.segments.single() as TextSegment).text.text)
    assertEquals(TopicKey(tid = 45150945, page = 1, floor = 0), chain.openInTopicKey(chain.chain.first()))
    assertEquals(listOf("3", "1"), transport.requests.map { it.url.substringAfter("page=").substringBefore("&") })
  }

  @Test
  fun `直接进入第二页也会补齐上一页原文预览`() = runTest(dispatcher) {
    val original = FloorSpec(1, 14, 1, content = "上一页原文")
    val reply = FloorSpec(2, 35, 2, content = "[b]Reply to [pid=1,45150945,1]Reply[/pid][/b]回复正文")
    val (client, transport) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = listOf(if (page == 1) original else reply), rows = 40))
    }
    val vm = viewModel(TopicKey(tid = 45150945, page = 2), FakeTopicDeps(client, appScope, dispatcher))
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val model = assertNotNull(vm.currentModel)
    val header = model.floors.single().body.segments.first() as QuoteSegment
    val preview = assertNotNull(header.preview)
    assertEquals("上一页原文", (preview.segments.single() as TextSegment).text.text)
    assertEquals(listOf("2", "1"), transport.requests.map { it.url.substringAfter("page=").substringBefore("&") })
    assertEquals(2, vm.page)
  }

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
    vm.goToPage(99)
    assertEquals(3, vm.page)
    vm.goToPage(0)
    assertEquals(1, vm.page)

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

    vm.reportVisibleFloor(2)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(5, fakes.historyDao.find(45150945)?.lastFloor, "只前进")

    vm.reportVisibleFloor(9)
    vm.flushReadFloor()
    advanceUntilIdle()
    assertEquals(9, fakes.historyDao.find(45150945)?.lastFloor)

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
    assertEquals(2, vm.page)

    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val target = assertNotNull(vm.scrollTarget)
    assertEquals(2, target.page)
    assertEquals(2, target.listIndex, "21 楼是这一页的第 2 条,前面还有 header 一格")
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

  @Test
  fun `带页码进场 —— 总页数先兜到进场页,首帧那一下夹逼吃不掉页码`() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945, page = 3), fakes)

    assertEquals(3, vm.page)
    assertEquals(3, vm.totalPages, "总页数没回来之前先兜到进场页")
    assertEquals(3, clampPage(vm.page, vm.totalPages), "goToPage 的夹逼不许动它")

    val settled = (vm.page - 1).coerceIn(0, pagerPageCount(vm.totalPages, vm.page) - 1)
    vm.goToPage(settled + 1)
    assertEquals(3, vm.page, "首帧的回写不该把页码打回第 1 页")

    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertEquals(listOf(3), transport.requests.mapNotNull { pageParamOf(it) }.take(1))
    assertEquals(3, vm.page)
    assertEquals(3, vm.totalPages)
    assertTrue(vm.pages[3] is PageState.Loaded)
  }

  @Test
  fun `在原帖中查看 —— 带第 3 页的楼号进场,数据到位后落到那一楼`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(
        pageEnvelope(
          page = page,
          floors = (40L..42L).map { FloorSpec(pid = 800000000 + it, lou = it, authorId = 1) },
          rows = 47,
        ),
      )
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945, floor = 40), fakes)
    assertEquals(3, vm.page, "40 楼 / 每页 20 → 第 3 页")

    val settled = (vm.page - 1).coerceIn(0, pagerPageCount(vm.totalPages, vm.page) - 1)
    vm.goToPage(settled + 1)

    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    assertEquals(3, vm.page)
    val target = assertNotNull(vm.scrollTarget, "楼层锚点要兑现,不能被 model.page 的守卫挡掉")
    assertEquals(3, target.page)
    assertEquals(1, target.listIndex, "40 楼是第 3 页的第 1 条,header 占了第 0 格")
  }

  @Test
  fun `跳楼给的是列表 index —— 目标楼离页顶远也算得准,热门回复区不多占一格`() =
    runTest(dispatcher) {
      val (client, _) = TopicFixtures.client { page, _ ->
        okJson(
          pageEnvelope(
            page = page,
            floors = ((page - 1) * 20 until page * 20).map {
              FloorSpec(pid = 800000000L + it, lou = it.toLong(), authorId = 1)
            },
            rows = 400,
            hotReplies = if (page == 1) {
              listOf(FloorSpec(pid = 900000003, lou = 3, authorId = 1))
            } else {
              emptyList()
            },
          ),
        )
      }
      val fakes = FakeTopicDeps(client, appScope, dispatcher)

      val far = viewModel(TopicKey(tid = 45150945, floor = 74), fakes)
      assertEquals(4, far.page)
      far.applyStyle(TopicFixtures.STYLE)
      advanceUntilIdle()
      val farTarget = assertNotNull(far.scrollTarget)
      assertEquals(4, farTarget.page)
      assertEquals(15, farTarget.listIndex, "74 楼是第 4 页第 15 条,前面只有 header 一格")

      val hot = viewModel(TopicKey(tid = 45150945, floor = 7), fakes)
      assertEquals(1, hot.page)
      hot.applyStyle(TopicFixtures.STYLE)
      advanceUntilIdle()
      assertTrue(hot.currentModel!!.hotReplies.isNotEmpty(), "这一页确实有热门回复区")
      val hotTarget = assertNotNull(hot.scrollTarget)
      assertEquals(8, hotTarget.listIndex, "7 楼是第 1 页第 8 条,热门回复区不额外占一行")
    }

  @Test
  fun `「回到那里」跨页跳楼 —— 目标页回来才兑现,兑现的是列表 index`() =
    runTest(dispatcher) {
      val (client, _) = TopicFixtures.client { page, _ ->
        okJson(
          pageEnvelope(
            page = page,
            floors = ((page - 1) * 20 until page * 20).map {
              FloorSpec(pid = 800000000L + it, lou = it.toLong(), authorId = 1)
            },
            rows = 400,
          ),
        )
      }
      val fakes = FakeTopicDeps(client, appScope, dispatcher)

      val first = viewModel(TopicKey(tid = 45150945), fakes)
      first.applyStyle(TopicFixtures.STYLE)
      advanceUntilIdle()
      first.reportVisibleFloor(74)
      first.flushReadFloor()
      advanceUntilIdle()

      val vm = viewModel(TopicKey(tid = 45150945, page = 1), fakes)
      vm.applyStyle(TopicFixtures.STYLE)
      advanceUntilIdle()
      assertEquals(1, vm.page)
      assertEquals(74L, vm.resumeFloor)

      vm.jumpToResume()
      assertEquals(4, vm.page, "74 楼 / 每页 20 → 第 4 页")
      assertNull(vm.scrollTarget, "目标页数据没到位之前不给滚动目标")

      advanceUntilIdle()
      val target = assertNotNull(vm.scrollTarget, "目标页回来了就要兑现")
      assertEquals(4, target.page)
      assertEquals(15, target.listIndex)
    }

  @Test
  fun `历史页带进度楼层进场 —— 落到那一楼,不再重复弹「上次读到」浮条`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)

    val first = viewModel(TopicKey(tid = 45150945), fakes)
    first.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    first.reportVisibleFloor(7)
    first.flushReadFloor()
    advanceUntilIdle()

    val resumed = viewModel(TopicKey(tid = 45150945, floor = 7), fakes)
    resumed.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertNull(resumed.resumeFloor, "已经带着进度楼层进场了,不再弹浮条")

    val elsewhere = viewModel(TopicKey(tid = 45150945, floor = 3), fakes)
    elsewhere.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertEquals(7L, elsewhere.resumeFloor)
  }

  private val imageFloor = FloorSpec(
    pid = 800000003,
    lou = 3,
    authorId = 77,
    authorName = "丙",
    content = "[img]https://img.nga.178.com/attachments/mon_202608/07/a.jpg[/img]",
  )

  private fun bookmarkDraft(pid: Long, lou: Long, note: String? = null, subject: String = "旧标题") =
    BookmarkDraft(
      tid = 45150945,
      pid = pid,
      lou = lou,
      author = "作者",
      summary = "摘要 $lou",
      note = note,
      subject = subject,
      boardName = "旧版块",
      favCode = null,
    )

  @Test
  fun `加书签 —— 带备注 留空 100 字截断 纯图片楼层摘要记图片`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = floors + imageFloor, rows = 4))
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    val model = vm.currentModel!!

    vm.openBookmarkDialog(model.floors[1])
    val dialog = assertNotNull(vm.bookmarkDialog)
    assertTrue(!dialog.editing)
    assertEquals(1L, dialog.lou)
    assertEquals("甲", dialog.author)
    assertEquals("一楼", dialog.summary)
    vm.saveBookmark("  记一下  ")
    advanceUntilIdle()
    assertNull(vm.bookmarkDialog)
    assertEquals("已加书签", vm.toast.value?.text)
    val saved = assertNotNull(fakes.bookmarkDao.find(45150945, 800000001))
    assertEquals("记一下", saved.note)
    assertEquals("一楼", saved.summary)
    assertEquals("测试主题", saved.subject)
    assertEquals("网事杂谈", saved.boardName)
    assertTrue(800000001L in vm.bookmarkedPids)

    vm.openBookmarkDialog(model.floors[2])
    vm.saveBookmark("   ")
    advanceUntilIdle()
    assertNull(fakes.bookmarkDao.find(45150945, 800000002)?.note)

    vm.openBookmarkDialog(model.floors[0])
    vm.saveBookmark("字".repeat(120))
    advanceUntilIdle()
    assertEquals(100, fakes.bookmarkDao.find(45150945, 0)?.note?.length)

    vm.openBookmarkDialog(model.floors[3])
    assertEquals("[图片]", vm.bookmarkDialog?.summary)
    vm.closeBookmarkDialog()
    assertNull(vm.bookmarkDialog)
  }

  @Test
  fun `编辑书签 —— 弹框预填原备注 保存后创建时间保留 摘要不回写`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    fakes.bookmarks.save(bookmarkDraft(pid = 800000001, lou = 1, note = "旧备注"), nowSeconds = 100)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.openBookmarkDialog(vm.currentModel!!.floors[1])
    val dialog = assertNotNull(vm.bookmarkDialog)
    assertTrue(dialog.editing)
    assertEquals("旧备注", dialog.note)
    assertEquals("摘要 1", dialog.summary)

    vm.saveBookmark("新备注")
    advanceUntilIdle()
    assertEquals("已更新备注", vm.toast.value?.text)
    val saved = assertNotNull(fakes.bookmarkDao.find(45150945, 800000001))
    assertEquals("新备注", saved.note)
    assertEquals(100L, saved.createdAt)
    assertEquals("摘要 1", saved.summary)
    assertEquals(1, fakes.bookmarks.observeTopic(45150945).first().size)
  }

  @Test
  fun `移除书签 —— 提示条带撤销 撤销原样恢复 ViewModel 清掉后撤销照样生效`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    fakes.bookmarks.save(bookmarkDraft(pid = 800000001, lou = 1, note = "备注"), nowSeconds = 100)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertTrue(800000001L in vm.bookmarkedPids)
    val original = assertNotNull(fakes.bookmarks.find(45150945, 800000001))
    assertEquals(100L, original.createdAt)

    vm.removeBookmark(vm.currentModel!!.floors[1])
    advanceUntilIdle()
    assertNull(fakes.bookmarkDao.find(45150945, 800000001))
    assertTrue(vm.bookmarkedPids.isEmpty())
    val snack = assertNotNull(vm.snackbar.value)
    assertEquals("已移除第 1 楼的书签", snack.text)
    assertEquals("撤销", snack.actionLabel)

    store.clear()
    snack.action!!.invoke()
    advanceUntilIdle()
    val restored = assertNotNull(fakes.bookmarks.find(45150945, 800000001))
    assertEquals(original, restored)
  }

  @Test
  fun `热门回复与正文流共享同一份书签状态`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ ->
      okJson(pageEnvelope(page = page, floors = floors, rows = 3, hotReplies = listOf(floors[2])))
    }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    val model = vm.currentModel!!
    val hot = model.hotReplies.single()
    val inStream = model.floors.single { it.pid == hot.pid }

    vm.openBookmarkDialog(hot)
    vm.saveBookmark("从热门回复加的")
    advanceUntilIdle()
    assertEquals("从热门回复加的", vm.bookmarkMarkOf(inStream)?.note)
    assertEquals("从热门回复加的", vm.bookmarkMarkOf(hot)?.note)
    vm.openBookmarkDialog(inStream)
    assertTrue(vm.bookmarkDialog!!.editing)
  }

  @Test
  fun `跳页弹层 —— 列出「上次读到」和按楼号排序的书签`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val first = viewModel(TopicKey(tid = 45150945), fakes)
    first.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    first.reportVisibleFloor(7)
    first.flushReadFloor()
    advanceUntilIdle()
    fakes.bookmarks.save(bookmarkDraft(pid = 800000002, lou = 2), nowSeconds = 100)
    fakes.bookmarks.save(bookmarkDraft(pid = 800000001, lou = 1, note = "先看这楼"), nowSeconds = 200)

    val vm = viewModel(TopicKey(tid = 45150945, page = 1), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val targets = vm.jumpTargets
    assertEquals(listOf(7L, 1L, 2L), targets.map { it.lou })
    assertTrue(targets[0].resume)
    assertEquals("上次读到", targets[0].title)
    assertEquals("第 7 楼", targets[0].detail)
    assertEquals("第 1 楼", targets[1].title)
    assertEquals("先看这楼", targets[1].detail, "有备注用备注")
    assertEquals("摘要 2", targets[2].detail, "没备注用摘要")
  }

  private fun twentyPerPage(page: Int, missing: Long? = null): String = pageEnvelope(
    page = page,
    floors = ((page - 1) * 20 until page * 20)
      .map { it.toLong() }
      .filter { it != missing }
      .map { FloorSpec(pid = 800000000L + it, lou = it, authorId = 1) },
    rows = 400,
  )

  @Test
  fun `书签跳楼 —— 同页直接给滚动目标 跨页等目标页回来 目标楼缺失落到邻楼并提示`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(twentyPerPage(page, missing = 25)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    vm.jumpToFloor(7)
    val same = assertNotNull(vm.scrollTarget)
    assertEquals(1, same.page)
    assertEquals(8, same.listIndex)
    assertEquals("已跳转到第 7 楼", vm.toast.value?.text)
    vm.consumeScrollTarget()
    vm.consumeToast()

    vm.jumpToFloor(74)
    assertEquals(4, vm.page)
    assertNull(vm.scrollTarget, "目标页数据没到位之前不给滚动目标")
    advanceUntilIdle()
    val far = assertNotNull(vm.scrollTarget)
    assertEquals(4, far.page)
    assertEquals(15, far.listIndex)
    assertEquals("已跳转到第 74 楼", vm.toast.value?.text)
    vm.consumeScrollTarget()
    vm.consumeToast()

    vm.jumpToFloor(25)
    advanceUntilIdle()
    val near = assertNotNull(vm.scrollTarget)
    assertEquals(2, near.page)
    assertEquals(6, near.listIndex, "25 楼没了,落到 26 楼:20..24 五条之后,header 占一格")
    assertEquals("第 25 楼已不存在,已跳到第 26 楼", vm.toast.value?.text)
  }

  @Test
  fun `只看此人模式下跳书签 —— 清掉只看状态并一次落到目标页 不经过第 1 页或进入前那页`() =
    runTest(dispatcher) {
      val (client, transport) = TopicFixtures.client { page, _ -> okJson(twentyPerPage(page)) }
      val fakes = FakeTopicDeps(client, appScope, dispatcher)
      val vm = viewModel(TopicKey(tid = 45150945, page = 3), fakes)
      vm.applyStyle(TopicFixtures.STYLE)
      advanceUntilIdle()

      vm.enterOnlyUser(vm.currentModel!!.floors[1])
      advanceUntilIdle()
      assertEquals(1, vm.page)
      assertNotNull(vm.onlyUser)
      val before = transport.requests.size

      vm.jumpToFloor(74)
      assertNull(vm.onlyUser)
      assertEquals(4, vm.page, "退出只看直接落到目标页")
      assertEquals(4, vm.totalPages, "总页数先兜到目标页,夹逼吃不掉它")
      advanceUntilIdle()

      val after = transport.requests.drop(before)
      assertTrue(after.isNotEmpty())
      assertTrue(after.none { it.url.contains("authorid=") }, "退出只看后的请求不再带作者过滤")
      assertTrue(after.mapNotNull { pageParamOf(it) }.all { it >= 4 }, "没有先回第 1 页或第 3 页")
      val target = assertNotNull(vm.scrollTarget)
      assertEquals(4, target.page)
      assertEquals(15, target.listIndex)
      assertEquals(20, vm.totalPages)
    }

  @Test
  fun `从书签进入 —— 不弹「上次读到」浮条`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    val first = viewModel(TopicKey(tid = 45150945), fakes)
    first.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    first.reportVisibleFloor(7)
    first.flushReadFloor()
    advanceUntilIdle()

    val fromBookmark = viewModel(TopicKey(tid = 45150945, floor = 2, fromBookmark = true), fakes)
    fromBookmark.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertNull(fromBookmark.resumeFloor)
    assertEquals(listOf(7L), fromBookmark.jumpTargets.map { it.lou }, "跳页弹层里仍然列出上次读到")

    val plain = viewModel(TopicKey(tid = 45150945, floor = 2), fakes)
    plain.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()
    assertEquals(7L, plain.resumeFloor)
  }

  @Test
  fun `分页加载时刷新书签里的主题标题和版块`() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { page, _ -> okJson(envelope(page)) }
    val fakes = FakeTopicDeps(client, appScope, dispatcher)
    fakes.bookmarks.save(bookmarkDraft(pid = 800000001, lou = 1, subject = "旧标题"), nowSeconds = 100)
    val vm = viewModel(TopicKey(tid = 45150945), fakes)
    vm.applyStyle(TopicFixtures.STYLE)
    advanceUntilIdle()

    val saved = assertNotNull(fakes.bookmarkDao.find(45150945, 800000001))
    assertEquals("测试主题", saved.subject)
    assertEquals("网事杂谈", saved.boardName)
  }
}

private fun pageParamOf(request: com.chasel.ng2n.core.net.HttpRequest): Int? =
  Regex("[?&]page=(\\d+)").find(request.url)?.groupValues?.get(1)?.toIntOrNull()

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
