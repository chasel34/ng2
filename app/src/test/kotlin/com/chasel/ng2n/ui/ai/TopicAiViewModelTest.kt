package com.chasel.ng2n.ui.ai

import androidx.lifecycle.ViewModelStore
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.data.account.AccountCrypto
import com.chasel.ng2n.data.ai.*
import com.chasel.ng2n.data.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.chasel.ng2n.data.ai.settings.AiKeyStore
import com.chasel.ng2n.data.topic.TopicPageParams
import com.chasel.ng2n.ui.topic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TopicAiViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()
  @Before fun before() { Dispatchers.setMain(dispatcher) }
  @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
  private val crypto = object : AccountCrypto {
    override fun encrypt(plaintext: ByteArray) = plaintext.decodeToString()
    override fun decrypt(blob: String) = blob.encodeToByteArray()
  }
  private class Model : TopicAiModel {
    val requests = Channel<Int>(Channel.UNLIMITED)
    val continueRun = Channel<Unit>(Channel.UNLIMITED)
    val histories = mutableListOf<List<AiExchange>>()
    val contexts = mutableListOf<TopicContext>()
    val callbacks = mutableListOf<suspend (AiStreamUpdate) -> Unit>()
    var cancelled = 0
    override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
      onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
      histories += history; contexts += context; callbacks += onFrame
      onStart(); onFrame(AiStreamUpdate.Text("部分回答")); requests.send(histories.size)
      try { continueRun.receive() } catch (cause: CancellationException) { cancelled++; throw cause }
      onFrame(AiStreamUpdate.Text("完整回答", true))
      return AiExchange(question, Message.Assistant("完整回答", ResponseMetaInfo.Empty))
    }
  }
  private fun setup(scope: CoroutineScope, keys: AiKeyStore, model: Model): Pair<TopicAiViewModel, FakeTopicDeps> {
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, scope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher)
    store.put("ai", vm)
    return vm to fakes
  }
  @Test fun personalEntryAndHistoryKeepTextOnlyPolicyAndOriginalUser() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { _, _ -> TopicFixtures.okJson("""{"data":{"__T":{"0":{"tid":42,"subject":"明确标题","author":"本人","authorid":7,"postdate":1704067200}}}}""") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val contexts = mutableListOf<TopicContext>()
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange = error("Requires forum tool session")
      override suspend fun runWithTools(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        tools: ForumToolSession?, onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        assertFalse(tools!!.allowImages)
        assertEquals("disabled", tools.execute("read_image", ForumToolArgs())["status"].toString().trim('"'))
        contexts += context
        val answer = """{"overview":"仅一条标题","processedSourceIds":["s1"],"interests":[],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"不推断未公开身份"}"""
        onStart(); onFrame(AiStreamUpdate.Text(answer, true))
        return AiExchange(question, Message.Assistant(answer, ResponseMetaInfo.Empty))
      }
    }
    fun create(name: String) = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put(name, it) }
    val first = create("personal")
    first.openPersona(-1, "匿名")
    runCurrent()
    assertFalse(first.state.value.visible)
    assertTrue(transport.requests.isEmpty())
    first.openPersona(7, "本人")
    first.state.first { !it.busy }
    assertEquals("个人", first.state.value.entryKind)
    assertEquals(AiSheetPosition.FULL, first.state.value.position)
    assertEquals("已完成", first.state.value.turns.single().status)
    assertEquals(1, contexts.single().sources.size)
    val reads = transport.requests.size
    val restored = create("personal-restored")
    restored.load(first.state.value.conversationId!!); runCurrent()
    assertFalse(restored.state.value.allowImages)
    restored.quickAction(BuiltinQuickActions.forEntry("个人").last())
    restored.state.first { !it.busy }
    assertEquals(2, contexts.size)
    assertEquals(reads, transport.requests.size)
    assertEquals("个人", contexts.last().entryKind)
  }

  @Test fun personalEntryWithoutAnySampleNeverRequestsTheModelAndStaysRetryable() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange = error("样本为 0 条时不得请求模型")
      override suspend fun runWithTools(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        tools: ForumToolSession?, onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange =
        error("样本为 0 条时不得请求模型")
    }
    fun open(body: (Int) -> String, card: String, status: String) {
      val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(body(page)) }
      val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
      val ledger = MemoryAiBudgetRepository()
      val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, MemoryAiConversationStore(), budgets = ledger)
      store.put(card, vm)
      vm.openPersona(7, "本人")
      dispatcher.scheduler.advanceUntilIdle()
      val turn = vm.state.value.turns.single()
      assertEquals(card, turn.card)
      assertEquals(status, turn.status)
      assertTrue(turn.incomplete)
      assertEquals(0, vm.state.value.context!!.sampleCount)
      assertTrue("零样本不得产生请求与费用", ledger.books.value.requests.isEmpty())
    }
    open({ """{"data":{"__T":{}}}""" }, "EMPTY", "没有可分析的发言")
    open({ """{"error":["用户不存在"]}""" }, "READ", "历史读取失败")
  }

  @Test fun personalReportCoversOnlyProcessedSamplesAndKeepsDraftForContinuation() = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { _, _ -> TopicFixtures.okJson(
      """{"data":{"__T":{"0":{"tid":42,"subject":"第一条标题","author":"本人","authorid":7,"postdate":1704067200},""" +
        """"1":{"tid":43,"subject":"第二条标题","author":"本人","authorid":7,"postdate":1704153600}}}}""") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val answers = ArrayDeque(listOf(
      """{"overview":"只处理了一条","processedSourceIds":["s1"],"interests":[{"title":"偏好","body":"仅一条","evidence":["s1"]}],"positions":[],"judgment":"证据不足","timeline":[],"boundary":"样本不完整"}""",
      """{"overview":"两条都已处理","processedSourceIds":["s1","s2"],"interests":[{"title":"偏好","body":"两条","evidence":["s1","s2"]}],"positions":[],"judgment":"仍然有限","timeline":[],"boundary":"样本边界"}""",
      "两条样本的标题分别是第一条标题、第二条标题。"))
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange = error("Requires forum tool session")
      override suspend fun runWithTools(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        tools: ForumToolSession?, onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        val answer = answers.removeFirst()
        onStart(); onFrame(AiStreamUpdate.Text(answer, true))
        return AiExchange(question, Message.Assistant(answer, ResponseMetaInfo.Empty))
      }
    }
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, MemoryAiConversationStore()).also { store.put("partial", it) }
    vm.openPersona(7, "本人")
    vm.state.first { !it.busy }
    val sampleCount = vm.state.value.context!!.ranges.first { it.label == "合计" }.amount.substringBefore(" /").toInt()
    assertEquals(2, sampleCount)
    val partial = vm.state.value.turns.single()
    assertTrue(partial.incomplete)
    assertEquals("个人报告尚未覆盖全部样本，可继续", partial.status)
    assertEquals(listOf("s1"), parsePersonaReport(partial.text, vm.state.value.context!!.sources, sampleCount)!!.processedSourceIds)
    vm.send("请继续处理剩余样本")
    vm.state.first { !it.busy }
    val full = vm.state.value.turns.last()
    assertFalse(full.incomplete)
    assertEquals("已完成", full.status)
    assertEquals(2, parsePersonaReport(full.text, vm.state.value.context!!.sources, sampleCount)!!.processedSourceIds.size)
    // 报告轮才需要说明待确认处理数；报告覆盖全部样本后，普通追问不再是报告轮。
    assertEquals(listOf(true, true), vm.state.value.turns.map { it.reportExpected })
    vm.send("列出两条样本的标题")
    vm.state.first { !it.busy }
    val followUp = vm.state.value.turns.last()
    assertFalse(followUp.reportExpected)
    assertEquals("已完成", followUp.status)
  }

  @Test fun queuedListInterruptedBeforePreparationCannotResumeWithDisplayMetadata() = unpreparedEntryRecovery(false)
  @Test fun queuedChainInterruptedBeforePreparationCannotResumeWithDisplayMetadata() = unpreparedEntryRecovery(true)
  private fun unpreparedEntryRecovery(chain: Boolean) = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { _, _ -> error("未恢复入口不得读取论坛") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val model = Model()
    val budgets = MemoryAiBudgetRepository()
    fun create(name: String) = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence, budgets = budgets)
      .also { store.put(name, it) }
    val first = create("queued-entry")
    val blocker = "preparation-blocker"
    AiExecutionQueue.shared.enter(blocker) {}
    try {
      if (chain) first.openChain(TopicPageParams(42, 1), listOf(
        com.chasel.ng2n.core.local.ChainNode(2, com.chasel.ng2n.core.local.ChainRole.CURRENT, false)), emptyList(), 2)
      else first.openList(listOf(com.chasel.ng2n.core.api.Topic(42, subject = "标题", author = "甲")), "版块", "最新回复", emptyList())
      runCurrent()
      assertEquals("等待执行 · 前面还有 1 个对话", first.state.value.turns.single().status)
      assertEquals(1, persistence.saved.size)
      assertTrue(persistence.working.isEmpty())
      first.stop(); runCurrent()
    } finally { AiExecutionQueue.shared.leave(blocker) }
    val restored = create("restored-unprepared")
    val id = first.state.value.conversationId!!
    restored.load(id); runCurrent()
    assertNotNull(restored.state.value.context)
    assertTrue(restored.state.value.context!!.sources.isEmpty())
    repeat(2) {
      restored.retry()
      restored.state.first { !it.busy }
      assertEquals("入口范围不可恢复，请从原内容重新发起分析", restored.state.value.turns.last().status)
      assertTrue(restored.state.value.turns.last().incomplete)
    }
    assertTrue(model.histories.isEmpty())
    assertTrue(transport.requests.isEmpty())
    assertTrue(budgets.books.value.requests.isEmpty())
    assertEquals(0, persistence.requests)
    assertNull(persistence.work(id, "context", "current"))
  }

  @Test fun missingKeyListSnapshotIsSavedAndResumesAfterSettingsWithoutReadingTopicZero() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { _, _ -> error("列表概览不读取主题正文") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto)
    val persistence = MemoryAiConversationStore()
    val model = Model()
    fun create(name: String) = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put(name, it) }
    val first = create("missing-key-list")
    first.openList(listOf(com.chasel.ng2n.core.api.Topic(42, subject = "标题", author = "甲")), "版块", "最新回复", emptyList())
    first.state.first { it.needsKey && !it.busy }
    assertTrue(model.histories.isEmpty())
    val restored = create("restored-key-list")
    restored.load(first.state.value.conversationId!!); runCurrent()
    keys.saveKey("offline-test")
    restored.retry()
    model.requests.receive()
    assertEquals(listOf(42L), model.contexts.last().sources.map { it.tid })
    assertTrue(transport.requests.isEmpty())
    model.continueRun.send(Unit); restored.state.first { !it.busy }
  }

  @Test fun listSnapshotAndChainRangeSurviveHistoryAndUseCorrectFilters() = runTest(dispatcher) {
    val (client, transport) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼"), TopicFixtures.FloorSpec(2, 2, 1, content = "当前楼层")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val model = Model()
    fun create(name: String) = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put(name, it) }
    val topics = mutableListOf(com.chasel.ng2n.core.api.Topic(42, subject = "摘要", author = "甲"))
    val list = create("list")
    list.openList(topics, "版块", "最新回复", emptyList())
    topics.clear()
    model.requests.receive()
    assertEquals(listOf(42L), model.contexts.last().sources.map { it.tid })
    assertTrue(transport.requests.isEmpty())
    model.continueRun.send(Unit); list.state.first { !it.busy }
    val chain = create("chain")
    val first = com.chasel.ng2n.core.api.TopicDetail(tid = 42, subject = "标题", attachBase = "", floors = listOf(
      com.chasel.ng2n.core.api.Floor(pid = 0, lou = 0, authorKey = "1", content = "主楼"),
      com.chasel.ng2n.core.api.Floor(pid = 2, lou = 2, authorKey = "1", content = "当前楼层")))
    chain.openChain(TopicPageParams(42, 1), listOf(com.chasel.ng2n.core.local.ChainNode(2, com.chasel.ng2n.core.local.ChainRole.CURRENT, true)), listOf(first), 2)
    model.requests.receive()
    assertEquals(listOf(0L, 2L), model.contexts.last().sources.map { it.pid })
    model.continueRun.send(Unit); chain.state.first { !it.busy }
    val floor = create("floor")
    floor.open(TopicPageParams(42, 1), 2, "楼层 · 2 楼")
    model.requests.receive(); model.continueRun.send(Unit); floor.state.first { !it.busy }
    val records = persistence.saved.values.map { it.conversation }
    listOf("列表", "楼层", "回复链").forEach { kind -> assertEquals(1, filterAiHistory(records, kind, "", null).size) }
    val restored = create("restored")
    restored.load(list.state.value.conversationId!!); runCurrent()
    assertEquals("列表", restored.state.value.entryKind)
    assertEquals("主题摘要", restored.state.value.context!!.ranges[1].label)
    assertEquals("summary", restored.state.value.context!!.sources.single().part)
    assertEquals(3, model.histories.size)
    val action = BuiltinQuickActions.forEntry("列表").first()
    restored.quickAction(action)
    model.requests.receive()
    assertEquals("列表", model.contexts.last().entryKind)
    assertEquals(action.skillId, restored.state.value.turns.last().quickActionId)
    model.continueRun.send(Unit); restored.state.first { !it.busy }
  }

  @Test fun discardReleasesUsageSubscriptionWithoutCancellingSharedScope() = usageSubscriptionLifecycle(false)
  @Test fun clearingViewModelReleasesUsageSubscriptionInSharedScope() = usageSubscriptionLifecycle(true)
  private fun usageSubscriptionLifecycle(clear: Boolean) = runTest(dispatcher) {
    val (client, _) = TopicFixtures.client { _, _ -> error("不应读取论坛") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val budgets = MemoryAiBudgetRepository()
    val keys = AiKeyStore(InMemoryPreferences(), crypto)
    fun create() = TopicAiViewModel(fakes.deps, keys, Model(), dispatcher,
      taskScope = backgroundScope, budgets = budgets)
    val survivor = create()
    runCurrent()
    assertEquals(1, budgets.books.subscriptionCount.value)
    repeat(3) { index ->
      val vm = create()
      val owner = ViewModelStore().also { it.put("session", vm) }
      runCurrent()
      assertEquals(2, budgets.books.subscriptionCount.value)
      vm.position(AiSheetPosition.COLLAPSED)
      runCurrent()
      assertEquals(2, budgets.books.subscriptionCount.value)
      if (clear) owner.clear() else vm.discard()
      val deletedState = vm.state.value
      runCurrent()
      assertEquals(1, budgets.books.subscriptionCount.value)
      budgets.begin("other-$index", null)
      runCurrent()
      assertEquals(deletedState, vm.state.value)
      assertEquals(budgets.books.value, survivor.state.value.usage)
      assertTrue(backgroundScope.isActive)
      owner.clear()
    }
    survivor.discard()
    runCurrent()
    assertEquals(0, budgets.books.subscriptionCount.value)
  }
  @Test fun historyPreviewNeverReadsWorkingBodyAndPersistsDeletedBadgeWithoutChangingAnswer() = historyPreview(false)
  @Test fun noteIdentityIsSavedAndRestoredWithoutWorkingBodyAndMissingNoteMarksHistoryDeleted() = historyPreview(true)
  private fun historyPreview(noteReference: Boolean) = runTest(dispatcher) {
    val note = com.chasel.ng2n.core.api.Floor(authorId = 2, authorKey = "2", content = "重新读取的贴条", postedAt = 1786075200, postedAtText = "2026-08-07 12:00")
    val part = noteSourcePart(note)
    val metadata = if (noteReference) """, "sourceParts":{"s1":"$part"}""" else ""
    val message = AiMessageEntity("saved", 0, Json.encodeToString(AiTurn("问题", "旧回答[[s1]]", "已完成")))
    var record = SavedAiConversation(AiConversationEntity("saved", "历史", "个人", 42, "原来源", 1, 1, "已完成", "第 1 页",
      """{"tid":42,"page":1,"readImageUrls":["https://example.com/a.jpg"]$metadata}""", null), listOf(message), emptyList(),
      listOf(AiSourceEntity("saved", "s1", 42, 0, 0, 1, "原作者", "昨天", 1, "hash")), null)
    val storage = object : AiConversationStore {
      override val conversations = kotlinx.coroutines.flow.flowOf(listOf(record.conversation))
      override suspend fun initialize() = Unit
      override suspend fun load(id: String) = record
      override suspend fun runById(id: String): AiRunEntity? = null
      override suspend fun work(id: String, kind: String, key: String): String? = error("预览不得读取 Room 工作正文")
      override suspend fun work(id: String, kind: String, key: String, payload: String) = error("预览不得写入模型工作正文")
      override suspend fun requestStarted(conversationId: String, runId: String) = error("预览不得请求模型")
      override suspend fun save(conversation: AiConversationEntity, messages: List<AiMessageEntity>, ranges: List<AiReadingRangeEntity>, sources: List<AiSourceEntity>, run: AiRunEntity?) {
        record = SavedAiConversation(conversation, messages, ranges, sources, run)
      }
    }
    var removed = false
    val (client, transport) = TopicFixtures.client { _, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(
      floors = if (removed && !noteReference) emptyList() else listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "重新读取的原文",
        comments = if (!noteReference || removed) emptyList() else listOf(TopicFixtures.FloorSpec(0, 0, 2, content = note.content)))))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, AiKeyStore(InMemoryPreferences(), crypto), Model(), dispatcher, storage)
    store.put("history-preview", vm)
    vm.load("saved"); runCurrent()
    val source = vm.state.value.context!!.sources.single()
    assertTrue(source.text.isEmpty())
    assertFalse(vm.state.value.allowImages)
    assertEquals(setOf("https://example.com/a.jpg"), vm.state.value.readImageUrls)
    val preview = vm.preview(source)
    assertEquals(if (noteReference) "ok" else "range", preview.status)
    assertEquals(if (noteReference) note.content else "重新读取的原文", preview.text)
    vm.draft("保留输入"); runCurrent()
    val restored = TopicAiViewModel(fakes.deps, AiKeyStore(InMemoryPreferences(), crypto), Model(), dispatcher, storage)
    store.put("restored-preview", restored)
    restored.load("saved"); runCurrent()
    val restoredSource = restored.state.value.context!!.sources.single()
    assertEquals(if (noteReference) part else null, restoredSource.part)
    removed = true
    assertEquals("unavailable", restored.preview(restoredSource).status)
    assertEquals(2, transport.requests.size)
    assertEquals("来源已删除", record.conversation.status)
    assertEquals("个人", record.conversation.kind)
    assertEquals(listOf(message), record.messages)
    assertEquals("hash", record.sources.single().contentHash)
  }

  @Test fun quickActionContinuesSameConversationAndDuplicateClickIsIgnored() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = Model()
    val (vm, _) = setup(backgroundScope, keys, model)
    vm.open(TopicPageParams(42, 1), null, "主题")
    model.requests.receive()
    val id = vm.state.value.conversationId
    model.continueRun.send(Unit)
    vm.state.first { !it.busy }
    val action = BuiltinQuickActions.forEntry(false).first()
    vm.quickAction(action); vm.quickAction(action)
    model.requests.receive()
    assertEquals(id, vm.state.value.conversationId)
    assertEquals(2, vm.state.value.turns.size)
    assertEquals(action.skillId, vm.state.value.turns.last().quickActionId)
    assertEquals(1, model.histories.last().size)
    model.continueRun.send(Unit)
    vm.state.first { !it.busy }
  }
  @Test fun missingKeyDoesNotReadForumOrSendModelAndDraftRemainsVisible() = runTest(dispatcher) {
    val model = Model()
    val (vm, fakes) = setup(backgroundScope, AiKeyStore(InMemoryPreferences(), crypto), model)
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { it.needsKey && !it.busy }
    assertTrue(model.histories.isEmpty())
    assertTrue(fakes.repository.loadedPages(42, null).isEmpty())
    assertEquals(1, vm.state.value.turns.size)
    assertTrue(vm.state.value.turns.single().incomplete)
  }
  @Test fun collapsingContinuesStoppingCancelsLateUpdatesAndRetryStartsFresh() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = Model()
    val (vm, _) = setup(backgroundScope, keys, model)
    vm.open(TopicPageParams(42, 1), null, "主题")
    model.requests.receive()
    vm.position(AiSheetPosition.COLLAPSED)
    assertTrue(vm.state.value.busy)
    vm.stop(); runCurrent()
    assertEquals(1, model.cancelled)
    model.callbacks.first()(AiStreamUpdate.Text("迟到内容"))
    assertEquals("部分回答", vm.state.value.turns.single().text)
    assertTrue(vm.state.value.turns.single().incomplete)
    vm.retry(); model.requests.receive()
    assertTrue(model.histories.last().isEmpty())
    model.continueRun.send(Unit)
    vm.state.first { !it.busy }
    assertEquals(2, vm.state.value.turns.size)
    vm.draft("继续解释")
    vm.send(); model.requests.receive()
    assertEquals(1, model.histories.last().size)
    assertEquals("继续解释", vm.state.value.turns.last().question)
    vm.send("重复点击")
    assertEquals(3, model.histories.size)
    model.continueRun.send(Unit)
    vm.state.first { !it.busy }
  }
  @Test fun reachingTheRunLimitKeepsTextAndSourcesMarksItAsALimitAndContinuesInTheSameAnalysis() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val budgets = MemoryAiBudgetRepository()
    val persistence = MemoryAiConversationStore()
    var calls = 0
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        calls++
        val budget = currentCoroutineContext()[AiRunBudget]!!
        budget.reserve(10_000, 0)
        onStart(); onFrame(AiStreamUpdate.Text("先给出部分结论。"))
        budget.settle(100, 10)
        if (calls == 1) throw AiRunLimitReached("本次分析已达到执行上限（40 步）")
        onFrame(AiStreamUpdate.Text("先给出部分结论。补完的完整回答", true))
        return AiExchange(question, Message.Assistant("先给出部分结论。补完的完整回答", ResponseMetaInfo(kotlin.time.Clock.System.now())))
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      subject = "主题标题", floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence, budgets = budgets)
    store.put("limit", vm)
    vm.open(TopicPageParams(42, 1), null, "主题标题")
    vm.state.first { !it.busy }
    val limited = vm.state.value.turns.single()
    assertEquals("LIMIT", limited.card)
    assertEquals("达到限制 · 本次分析已达到执行上限（40 步）", limited.status)
    assertTrue(limited.incomplete)
    assertEquals("先给出部分结论。", limited.text)
    assertEquals("先给出部分结论。",
      Json.decodeFromString<AiTurn>(persistence.saved.getValue(vm.state.value.conversationId!!).messages.single().payload).text)
    val analysis = limited.analysisId
    // 条目标题已经是主题名，副标题不再重复一遍。
    assertEquals("主题 42 · 用户1", persistence.saved.getValue(vm.state.value.conversationId!!).conversation.source)

    vm.retry()
    vm.state.first { !it.busy && it.turns.lastOrNull()?.status == "已完成" }
    assertEquals(2, calls)
    assertEquals(analysis, vm.state.value.turns.last().analysisId)
    assertNull(vm.state.value.turns.last().card)
    assertEquals("先给出部分结论。补完的完整回答", vm.state.value.turns.last().text)
  }

  @Test fun budgetRequiresConfirmationContinuesSameAnalysisAndStopKeepsSavedResult() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val budgets = MemoryAiBudgetRepository()
    val persistence = MemoryAiConversationStore()
    var calls = 0
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        calls++
        val budget = currentCoroutineContext()[AiRunBudget]!!
        budget.reserve(10_000, 0)
        onStart(); onFrame(AiStreamUpdate.Text("已有结论"))
        if (calls == 1 || calls == 3) throw AiBudgetExceeded(false)
        budget.settle(100, 10)
        return AiExchange(question, Message.Assistant("已有结论", ResponseMetaInfo(kotlin.time.Clock.System.now())))
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence, budgets = budgets)
    store.put("budget", vm)
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { !it.busy && it.turns.lastOrNull()?.card == "budget" }
    assertEquals("已有结论", persistence.saved.values.single().messages.single().let { Json.decodeFromString<AiTurn>(it.payload).text })
    val analysis = vm.state.value.turns.single().analysisId
    vm.retry(); vm.send("绕过确认"); runCurrent()
    assertEquals(1, calls)
    vm.decideBudget(true)
    vm.state.first { !it.busy && it.turns.lastOrNull()?.status == "已完成" }
    assertEquals(analysis, vm.state.value.turns.last().analysisId)
    assertEquals(100_000L, budgets.books.value.analyses.single().limit)
    assertEquals(2, budgets.books.value.requests.size)
    assertEquals(2, calls)
    vm.send("新追问")
    vm.state.first { !it.busy && it.turns.lastOrNull()?.card == "budget" }
    vm.decideBudget(false); runCurrent()
    vm.retry(); runCurrent()
    assertEquals(3, calls)
    assertEquals("已到此为止", vm.state.value.turns.last().status)
  }

  @Test fun stopDuringAllowanceCommitDoesNotResume() = interruptedDecision(false, false)
  @Test fun stopDuringDecisionSaveDoesNotResume() = interruptedDecision(true, false)
  @Test fun discardDuringAllowanceCommitDoesNotRestoreConversation() = interruptedDecision(false, true)
  @Test fun discardDuringDecisionSaveDoesNotRestoreConversation() = interruptedDecision(true, true)
  private fun interruptedDecision(duringSave: Boolean, discard: Boolean) = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val memory = MemoryAiConversationStore()
    val ledger = MemoryAiBudgetRepository()
    val waiting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var pause = false
    var used = false
    suspend fun stall() {
      if (pause && !used) { used = true; waiting.complete(Unit); withContext(NonCancellable) { release.await() } }
    }
    val budgets = object : AiBudgetRepository by ledger {
      override suspend fun decide(id: String, more: Boolean, amount: Long) {
        ledger.decide(id, more, amount)
        if (!duringSave) stall()
      }
    }
    val persistence = object : AiConversationStore by memory {
      override suspend fun save(conversation: AiConversationEntity, messages: List<AiMessageEntity>, ranges: List<AiReadingRangeEntity>, sources: List<AiSourceEntity>, run: AiRunEntity?) {
        if (duringSave) stall()
        memory.save(conversation, messages, ranges, sources, run)
      }
    }
    var calls = 0
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        calls++; throw AiBudgetExceeded(false)
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence, budgets = budgets)
    store.put("decision", vm)
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { !it.busy && it.turns.lastOrNull()?.card == "budget" }
    pause = true
    vm.decideBudget(true)
    waiting.await()
    if (discard) vm.discard() else vm.stop()
    release.complete(Unit)
    runCurrent()
    assertEquals(1, calls)
    assertFalse(vm.state.value.busy)
    if (discard) { assertNull(vm.state.value.conversationId); assertTrue(vm.state.value.turns.isEmpty()) }
    else assertEquals("已停止 · 未完成", vm.state.value.turns.last().status)
  }

  @Test fun correctedDailyAllowanceContinuesOriginalAnalysis() = correctedSettings("daily")
  @Test fun correctedKeyContinuesOriginalAnalysis() = correctedSettings("AUTH")
  @Test fun correctedBalanceContinuesOriginalAnalysis() = correctedSettings("BALANCE")
  private fun correctedSettings(card: String) = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val ledger = MemoryAiBudgetRepository()
    val persistence = MemoryAiConversationStore()
    var corrected = false
    var modelCalls = 0
    val budgets = object : AiBudgetRepository by ledger {
      override suspend fun reserve(analysis: String, input: Long, images: Int, retry: Boolean, output: Long?): String {
        if (card == "daily" && !corrected) throw AiBudgetExceeded(true)
        return ledger.reserve(analysis, input, images, retry, output)
      }
    }
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        val budget = currentCoroutineContext()[AiRunBudget]!!
        budget.reserve(100, 0)
        budget.sending()
        modelCalls++
        if (!corrected) throw IllegalStateException(if (card == "AUTH") "HTTP 401" else "HTTP 402")
        onStart(); onFrame(AiStreamUpdate.Text("恢复回答")); budget.settle(100, 10)
        return AiExchange(question, Message.Assistant("恢复回答", ResponseMetaInfo(kotlin.time.Clock.System.now())))
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence, budgets = budgets)
    store.put("correction", vm)
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { !it.busy && it.turns.lastOrNull()?.card == card }
    val analysis = vm.state.value.turns.last().analysisId
    val runId = persistence.saved.values.single().run!!.id
    val before = modelCalls
    if (card == "daily") {
      vm.retry()
      vm.state.first { !it.busy && it.turns.size == 2 }
      assertEquals(before, modelCalls)
      assertEquals("daily", vm.state.value.turns.last().card)
    }
    corrected = true
    if (card == "AUTH") keys.saveKey("corrected-offline-key")
    runCurrent()
    assertEquals(before, modelCalls)
    vm.retry()
    vm.state.first { !it.busy && it.turns.lastOrNull()?.status == "已完成" }
    assertEquals(analysis, vm.state.value.turns.last().analysisId)
    assertEquals(1, ledger.books.value.analyses.size)
    assertEquals(before + 1, modelCalls)
    assertNotEquals(runId, persistence.saved.values.single().run!!.id)
    assertNotNull(persistence.saved.values.single().run!!.resumeFrom)
  }

  private fun filteredTopic(scope: CoroutineScope, keys: AiKeyStore, model: Model,
    targetAvailable: Boolean = true): Triple<TopicViewModel, TopicAiViewModel, com.chasel.ng2n.core.net.RecordingTransport> {
    val starter = TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")
    val visibleAuthor = TopicFixtures.FloorSpec(1, 1, 9, content = "前页发言")
    val target = TopicFixtures.FloorSpec(74, 74, 9, content = "选中的第 74 楼")
    val (client, transport) = TopicFixtures.client { page, uri ->
      val query = uri.rawQuery.orEmpty()
      val floors = when {
        query.contains("pid=74") -> if (targetAvailable) listOf(target) else listOf(starter)
        query.contains("authorid=9") -> listOf(target, TopicFixtures.FloorSpec(75, 75, 9))
        else -> listOf(starter, visibleAuthor)
      }
      TopicFixtures.okJson(TopicFixtures.pageEnvelope(tid = 42, page = page, floors = floors, rows = 80))
    }
    val fakes = FakeTopicDeps(client, scope, dispatcher)
    val topic = TopicViewModel(com.chasel.ng2n.ui.nav.TopicKey(tid = 42, fav = "scope-fav"), fakes.deps, dispatcher)
    store.put("topic", topic)
    topic.applyStyle(TopicFixtures.STYLE)
    val ai = TopicAiViewModel(fakes.deps, keys, model, dispatcher)
    store.put("ai", ai)
    return Triple(topic, ai, transport)
  }

  @Test fun selectedFloorInAuthorFilteredFirstPageIsReadByPid() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = Model()
    val (topic, ai, transport) = filteredTopic(backgroundScope, keys, model)
    advanceUntilIdle()
    topic.enterOnlyUser(checkNotNull(topic.currentModel).floors.last())
    advanceUntilIdle()
    assertEquals(1, topic.page)
    assertEquals(74L, checkNotNull(topic.currentModel).floors.first().pid)
    ai.open(topic.paramsFor(topic.page), 74, "楼层")
    model.requests.receive()
    assertEquals(listOf(0L, 74L), model.contexts.single().sources.map { it.pid })
    assertTrue(ai.state.value.steps[1].done)
    val request = transport.requests.last { it.url.contains("pid=74") }.url
    assertFalse(request.contains("authorid="))
    assertTrue(request.contains("fav=scope-fav"))
    ai.stop()
  }

  @Test fun topicOverviewRetainsTheAuthorFilteredCurrentPage() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = Model()
    val (topic, ai, _) = filteredTopic(backgroundScope, keys, model)
    advanceUntilIdle()
    topic.enterOnlyUser(checkNotNull(topic.currentModel).floors.last())
    advanceUntilIdle()
    ai.open(topic.paramsFor(topic.page), null, "主题")
    model.requests.receive()
    assertEquals(listOf(0L, 1L, 74L, 75L), model.contexts.single().sources.map { it.pid })
    assertTrue(ai.state.value.steps.any { it.done && it.label == "当前页 · 只看用户 9 · 第 1 页" })
    assertTrue(model.contexts.single().material().contains("只看用户 9"))
    ai.stop()
  }

  @Test fun missingSelectedFloorIsNotReportedReadAndDoesNotInvokeTheModel() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = Model()
    val (_, ai, _) = filteredTopic(backgroundScope, keys, model, targetAvailable = false)
    ai.open(TopicPageParams(42, 1, authorId = 9), 74, "楼层")
    ai.state.first { !it.busy }
    assertTrue(model.contexts.isEmpty())
    assertFalse(ai.state.value.steps[1].done)
    assertTrue(ai.state.value.steps[1].label.contains("未读取"))
    assertTrue(ai.state.value.turns.last().status.contains("选中楼层未读取"))
    assertTrue(ai.state.value.turns.last().incomplete)
  }

  @Test fun storageFailureKeepsDraftAndBlocksFurtherModelCallsUntilExplicitSave() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val model = Model()
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence)
    store.put("persistent", vm)
    vm.open(TopicPageParams(42, 1), null, "标题")
    model.requests.receive()
    persistence.fail = true
    model.continueRun.send(Unit)
    vm.state.first { it.unsaved && !it.busy }
    assertEquals("保存失败 · 未保存", vm.state.value.turns.last().status)
    assertNull(vm.state.value.turns.last().decision)
    assertTrue(vm.state.value.turns.last().text.isNotEmpty())
    vm.send("不能发送"); vm.retry(); runCurrent()
    assertEquals(1, model.histories.size)
    persistence.fail = false
    vm.retrySave(); runCurrent()
    assertFalse(vm.state.value.unsaved)
    val saved = persistence.saved.values.single()
    assertTrue(saved.sources.all { it.contentHash.length == 64 })
    val resumed = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence)
    store.put("resumed", resumed)
    resumed.load(saved.conversation.id); runCurrent()
    assertEquals(1, model.histories.size)
    assertEquals(AiSheetPosition.FULL, resumed.state.value.position)
    assertTrue(resumed.state.value.context!!.sources.all { it.text.isEmpty() })
    assertEquals(vm.state.value.turns.last().text, resumed.state.value.turns.last().text)
  }

  @Test fun sameTopicCreatesSeparateConversationsAndOpeningHistoryDoesNotPay() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val model = Model()
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page, floors = listOf(TopicFixtures.FloorSpec(0, 0, 1)))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    fun vm(name: String) = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put(name, it) }
    val first = vm("first"); first.open(TopicPageParams(42, 1), null, "同一主题")
    model.requests.receive()
    first.position(AiSheetPosition.COLLAPSED)
    first.position(AiSheetPosition.HALF)
    assertEquals(1, persistence.saved.size)
    first.stop(); runCurrent()
    val second = vm("second"); second.open(TopicPageParams(42, 1), null, "同一主题")
    model.requests.receive(); second.stop(); runCurrent()
    assertEquals(2, persistence.saved.size)
    assertNotEquals(first.state.value.conversationId, second.state.value.conversationId)
    vm("history").load(first.state.value.conversationId!!); runCurrent()
    assertEquals(2, model.histories.size)
  }

  @Test fun historyContinuationUsesNewestRunAfterAnotherDisconnection() = resumedRunUsesNewestProgress(false)
  @Test fun historyContinuationUsesNewestRunAfterStopping() = resumedRunUsesNewestProgress(true)

  private fun resumedRunUsesNewestProgress(stop: Boolean) = runTest(dispatcher) {
    val persistence = MemoryAiConversationStore()
    val params = TopicPageParams(42, 1)
    val initial = TopicContext(emptyList(), null, 0, listOf(1))
    persistence.save(com.chasel.ng2n.data.db.AiConversationEntity("history", "主题", "主题", 42, "主题 42", 1, 1,
      "已中断", "第 1 页", kotlinx.serialization.json.Json.encodeToString(TopicPageParams.serializer(), params), null),
      listOf(com.chasel.ng2n.data.db.AiMessageEntity("history", 0,
        kotlinx.serialization.json.Json.encodeToString(AiTurn.serializer(), AiTurn("原问题", "A 的草稿", "已中断", true)))),
      emptyList(), emptyList(), com.chasel.ng2n.data.db.AiRunEntity("A", "history", "interrupted", "生成回答", 1))
    persistence.work("history", "context", "current", kotlinx.serialization.json.Json.encodeToString(TopicContext.serializer(), initial))
    persistence.work("history", "checkpoint", "A", "A 检查点")
    persistence.work("history", "tool:A", "读取", "A 工具结果")
    val started = Channel<String>(Channel.UNLIMITED)
    val observed = mutableListOf<Pair<String?, String?>>()
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        val binding = checkNotNull(kotlin.coroutines.coroutineContext[AiRunPersistence])
        observed += binding.priorWork("checkpoint") to binding.priorWork("tool", "读取")
        if (observed.size == 1) {
          persistence.work("history", "checkpoint", binding.runId, "B 检查点")
          persistence.work("history", "tool:${binding.runId}", "读取", "B 工具结果")
          onFrame(AiStreamUpdate.Text("B 的草稿"))
          started.send(binding.runId)
          if (stop) awaitCancellation() else error("再次断线")
        }
        started.send(binding.runId)
        return AiExchange(question, Message.Assistant("完成", ResponseMetaInfo.Empty))
      }
    }
    val (client, _) = TopicFixtures.client { _, _ -> error("已有工作上下文，不应重读论坛") }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence)
    store.put("history-continuation", vm)
    vm.load("history"); runCurrent()
    assertTrue(observed.isEmpty())
    vm.retry()
    val b = started.receive()
    if (stop) vm.stop()
    vm.state.first { !it.busy }; runCurrent()
    vm.retry()
    val c = started.receive()
    vm.state.first { !it.busy }; runCurrent()
    assertEquals("A", persistence.runs.getValue(b).resumeFrom)
    assertEquals(b, persistence.runs.getValue(c).resumeFrom)
    assertEquals(listOf("A 检查点" to "A 工具结果", "B 检查点" to "B 工具结果"), observed)
    assertEquals("B 的草稿", vm.state.value.turns[1].text)
  }

  @Test fun webSearchFailureKeepsForumAnswerAndRaisesNotice() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange = error("Requires forum tool session")
      override suspend fun runWithTools(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        tools: ForumToolSession?, onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        onFrame(AiStreamUpdate.Tool(ToolCallRow("c1", "search_web", "出口 统计 月度")))
        onFrame(AiStreamUpdate.Tool(ToolCallRow("c1", "search_web", "出口 统计 月度", "challenge",
          "搜索页返回人机验证，不计为零结果；未改用付费搜索服务")))
        onStart()
        val answer = "外部核查未做，仅依据论坛发言 [[s1]]。"
        onFrame(AiStreamUpdate.Text(answer, true))
        return AiExchange(question, Message.Assistant(answer, ResponseMetaInfo.Empty))
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher).also { store.put("search", it) }
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { !it.busy }
    val turn = vm.state.value.turns.single()
    assertEquals("已完成", turn.status)
    assertTrue(turn.text.contains("外部核查未做"))
    assertTrue(webSearchUnavailable(turn.tools))
    assertEquals("challenge", turn.tools.single().status)
  }

  @Test fun webSourcesKeepUrlAndReadStateAcrossHistoryReload() = runTest(dispatcher) {
    val keys = AiKeyStore(InMemoryPreferences(), crypto).also { it.saveKey("offline-test") }
    val persistence = MemoryAiConversationStore()
    val web = AiSource("s2", 0, 0, 0, 1, "stats.gov.cn", "", "网页正文摘录", emptyList(), "web",
      AiWebSource("https://www.stats.gov.cn/sj/zxfb/", "月度社会消费品零售数据发布", bodyRead = true))
    val model = object : TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange = error("Requires forum tool session")
      override suspend fun runWithTools(key: String, context: TopicContext, history: List<AiExchange>, question: String,
        tools: ForumToolSession?, onFrame: suspend (AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): AiExchange {
        onFrame(AiStreamUpdate.Tool(ToolCallRow("c1", "read_webpage", "stats.gov.cn", "ok", "正文 3420 字", "s2")))
        onFrame(AiStreamUpdate.Sources(context.sources + web))
        onStart()
        val answer = "外部资料见 [[s2]]。"
        onFrame(AiStreamUpdate.Text(answer, true))
        return AiExchange(question, Message.Assistant(answer, ResponseMetaInfo.Empty))
      }
    }
    val (client, _) = TopicFixtures.client { page, _ -> TopicFixtures.okJson(TopicFixtures.pageEnvelope(page = page,
      floors = listOf(TopicFixtures.FloorSpec(0, 0, 1, content = "主楼")))) }
    val fakes = FakeTopicDeps(client, backgroundScope, dispatcher)
    val vm = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put("webhistory", it) }
    vm.open(TopicPageParams(42, 1), null, "主题")
    vm.state.first { !it.busy }
    val id = vm.state.value.conversationId!!
    assertEquals(web.web, vm.state.value.context!!.sources.single { it.id == "s2" }.web)

    val reopened = TopicAiViewModel(fakes.deps, keys, model, dispatcher, persistence).also { store.put("webreload", it) }
    reopened.load(id)
    reopened.state.first { it.conversationId == id }
    val restored = reopened.state.value.context!!.sources.single { it.id == "s2" }
    assertEquals("https://www.stats.gov.cn/sj/zxfb/", restored.web!!.url)
    assertEquals("月度社会消费品零售数据发布", restored.web.title)
    assertTrue(restored.web.bodyRead)
    assertEquals("stats.gov.cn", aiSourceLabel(restored))
  }

}
