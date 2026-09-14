package com.chasel.ng2n.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.core.api.fetchBlockWords
import com.chasel.ng2n.core.api.officialFilterRules
import com.chasel.ng2n.data.ai.*
import com.chasel.ng2n.data.ai.settings.AiKeyStore
import com.chasel.ng2n.data.topic.TopicPageParams
import com.chasel.ng2n.ui.topic.TopicDeps
import com.chasel.ng2n.ui.topic.toMatchRule
import com.chasel.ng2n.data.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@kotlinx.serialization.Serializable
data class ReadingStep(val label: String, val done: Boolean = false)
@kotlinx.serialization.Serializable
data class AiTurn(val question: String, val text: String = "", val status: String = "读取内容", val incomplete: Boolean = false,
  val analysisId: String? = null, val card: String? = null, val decision: String? = null, val addition: Long = 0,
  val quickActionId: String? = null, val skillsVersion: String? = null, val tools: List<ToolCallRow> = emptyList(), val thinkingStarted: Long? = null, val thoughtSeconds: Long? = null,
  val reportExpected: Boolean = false)
data class TopicAiState(val visible: Boolean = false, val position: AiSheetPosition = AiSheetPosition.HALF,
  val entryKind: String = "主题", val floorEntry: Boolean = false, val title: String = "", val steps: List<ReadingStep> = emptyList(), val context: TopicContext? = null,
  val turns: List<AiTurn> = emptyList(), val busy: Boolean = false, val needsKey: Boolean = false,
  val draft: String = "", val conversationId: String? = null, val unsaved: Boolean = false, val interruption: String? = null, val sourceUnavailable: Boolean = false, val allowImages: Boolean = true, val readImageUrls: Set<String> = emptySet(), val usage: AiBudgetBook = AiBudgetBook())

class TopicAiViewModel(private val deps: TopicDeps, private val keys: AiKeyStore,
  private val runtime: TopicAiModel, private val compute: CoroutineDispatcher = Dispatchers.Default,
  private val store: AiConversationStore? = null, private val taskScope: CoroutineScope? = null,
  private val budgets: AiBudgetRepository? = null,
  private val register: (String, TopicAiViewModel) -> Unit = { _, _ -> }) : ViewModel() {
  private val mutable = MutableStateFlow(TopicAiState())
  val state = mutable.asStateFlow()
  private var job: Job? = null
  @Volatile private var generation = 0L
  private var entry: TopicPageParams? = null
  private var selected: Long? = null
  private var tools: ForumToolSession? = null
  private var modelContext: TopicContext? = null
  private var personaCovered = false
  private var prepareEntry: (suspend () -> TopicContext)? = null
  private val history = mutableListOf<AiExchange>()

  private var conversationKind = "主题"
  private var createdAt = 0L
  private var run: AiRunEntity? = null
  private val sourceRecords = java.util.concurrent.ConcurrentHashMap<String, AiSourceEntity>()
  private val scope get() = taskScope ?: viewModelScope

  private val usageJob = budgets?.let { budgets ->
    scope.launch {
      budgets.books.collect { book -> mutable.update { it.copy(usage = book) } }
    }
  }
  override fun onCleared() {
    usageJob?.cancel()
    super.onCleared()
  }
  private val saveLock = Mutex()
  private suspend fun save(finishingDecision: Boolean = false) = withContext(compute) { saveLock.withLock {
    val storage = store ?: return@withLock
    val state = state.value.let { if (finishingDecision) it.copy(busy = false) else it }
    val id = state.conversationId ?: return@withLock
    val params = entry ?: return@withLock
    val time = System.currentTimeMillis()
    state.context?.sources?.forEach { source ->
      val hash = java.security.MessageDigest.getInstance("SHA-256").digest(source.text.toByteArray()).joinToString("") { "%02x".format(it) }
      if (!sourceRecords.containsKey(source.id) || (source.text.isNotEmpty() && sourceRecords[source.id]?.contentHash != hash)) sourceRecords[source.id] = AiSourceEntity(
        id, source.id, source.tid, source.pid, source.floor, source.page, source.author, source.postedAt, time,
        hash)
    }
    // 标题缺失时只保留一次 tid 占位；与条目标题相同的主题名、空段和尾部分隔符都不写入副标题。
    val topicTitle = state.context?.title?.takeIf { it.isNotBlank() } ?: state.title
    val subtitle = ((if (conversationKind in listOf("列表", "个人")) listOf(state.title)
      else listOf(topicTitle.takeIf { it != "主题 ${params.tid}" && it != state.title }, "主题 ${params.tid}")) + listOf(
      selected?.let { "楼层 $it" },
      sourceRecords.values.map { it.author }.filter { it.isNotBlank() }.distinct().joinToString("、")))
      .filterNot { it.isNullOrBlank() }.joinToString(" · ")
    storage.save(AiConversationEntity(id, state.title, conversationKind, params.tid, subtitle, createdAt, time,
      if (state.busy) "生成中" else if (state.sourceUnavailable) "来源已删除" else if (state.interruption != null) "已中断" else state.turns.lastOrNull()?.status.orEmpty(),
      state.steps.joinToString(" · ") { it.label }, buildJsonObject {
        Json.encodeToJsonElement(params).jsonObject.forEach { (key, value) -> put(key, value) }
        put("sourceParts", buildJsonObject { state.context?.sources?.forEach { source -> source.part?.let { put(source.id, it) } } })
        put("sourceWeb", buildJsonObject { state.context?.sources?.forEach { source -> source.web?.let { put(source.id, Json.encodeToJsonElement(it)) } } })
        put("readImageUrls", Json.encodeToJsonElement(state.readImageUrls))
        put("entryRanges", Json.encodeToJsonElement(state.context?.ranges.orEmpty()))
        state.context?.note?.let { put("entryNote", it) }
        put("readBlocked", state.context?.blocked ?: 0)
        put("sampleCount", state.context?.sampleCount ?: 0)
        put("preparedSources", state.context?.prepared ?: 0)
        put("readPages", Json.encodeToJsonElement(state.context?.pages.orEmpty()))
      }.toString(), selected, state.draft),
      state.turns.mapIndexed { index, turn -> AiMessageEntity(id, index, Json.encodeToString(turn)) },
      state.steps.mapIndexed { index, step -> AiReadingRangeEntity(id, index, step.label, step.done) }, sourceRecords.values.toList(), run)
  } }

  private fun storageFailed() {
    generation++; job?.cancel()
    run = run?.copy(status = "interrupted")
    mutable.update { state -> state.copy(busy = false, unsaved = true,
      turns = state.turns.mapIndexed { index, turn -> if (index == state.turns.lastIndex) turn.copy(incomplete = true, status = "保存失败 · 未保存", decision = null, card = null,
        tools = turn.tools.map { if (it.status == "running") it.copy(status = "interrupted", detail = "保存失败，已暂停") else it }) else turn }) }
  }

  fun load(id: String) {
    if (state.value.conversationId == id) { position(AiSheetPosition.FULL); return }
    scope.launch {
      try {
        store?.initialize()
        val saved = store?.load(id) ?: run {
          mutable.value = TopicAiState(visible = true, position = AiSheetPosition.FULL, title = "对话不可用",
            interruption = "对话已删除或无法读取。请返回历史页。")
          return@launch
        }
        modelContext = null; tools = null; prepareEntry = null; history.clear(); personaCovered = false
        entry = Json { ignoreUnknownKeys = true }.decodeFromString(saved.conversation.entryJson)
        val entryMetadata = Json.parseToJsonElement(saved.conversation.entryJson).jsonObject
        conversationKind = saved.conversation.kind
        selected = saved.conversation.selectedPid
        createdAt = saved.conversation.createdAt
        run = saved.run
        sourceRecords.clear(); saved.sources.forEach { sourceRecords[it.sourceId] = it }
        val webSources = entryMetadata["sourceWeb"]?.jsonObject.orEmpty()
        val context = TopicContext(saved.sources.map { AiSource(it.sourceId, it.tid, it.pid, it.floor, it.page, it.author, it.postedAt, "", emptyList(), entryMetadata["sourceParts"]?.jsonObject?.get(it.sourceId)?.jsonPrimitive?.content,
          webSources[it.sourceId]?.let { web -> Json.decodeFromJsonElement<AiWebSource>(web) }) }, null, entryMetadata["readBlocked"]?.jsonPrimitive?.intOrNull ?: 0, entryMetadata["readPages"]?.jsonArray?.map { it.jsonPrimitive.int } ?: saved.sources.map { it.page }.distinct(), title = saved.conversation.title, entryKind = conversationKind,
          ranges = entryMetadata["entryRanges"]?.let { Json.decodeFromJsonElement<List<AiReadingRow>>(it) }.orEmpty(),
          note = entryMetadata["entryNote"]?.jsonPrimitive?.content,
          sampleCount = entryMetadata["sampleCount"]?.jsonPrimitive?.intOrNull ?: 0,
          prepared = entryMetadata["preparedSources"]?.jsonPrimitive?.intOrNull ?: saved.sources.size)
        val interrupted = saved.run?.takeIf { it.status == "interrupted" || it.status == "running" }
        mutable.value = TopicAiState(usage = state.value.usage, visible = true, position = AiSheetPosition.FULL, title = saved.conversation.title, entryKind = conversationKind, floorEntry = selected != null, allowImages = saved.conversation.kind != "个人",
          readImageUrls = entryMetadata["readImageUrls"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
          steps = saved.ranges.map { ReadingStep(it.label, it.done) }, context = context,
          turns = saved.messages.map { Json.decodeFromString<AiTurn>(it.payload) }.mapIndexed { index, turn ->
            if (interrupted != null && index == saved.messages.lastIndex) turn.copy(status = "已中断", incomplete = true,
              tools = turn.tools.map { if (it.status == "running") it.copy(status = "interrupted", detail = "读取中断") else it }) else turn
          }, draft = saved.conversation.draft, conversationId = id, sourceUnavailable = saved.conversation.status == "来源已删除",
          interruption = interrupted?.let { "上次运行已中断：停在${it.step}。应用进程已结束，已有文字与来源已保留。继续可能产生新的费用。" })
        personaCovered = conversationKind == "个人" && context.sampleCount > 0 && state.value.turns.any { turn ->
          (parsePersonaReport(turn.text, context.sources, context.sampleCount)?.processedSampleCount(context.sampleCount) ?: 0) >= context.sampleCount
        }
        register(id, this@TopicAiViewModel)
      } catch (e: CancellationException) { throw e }
      catch (_: Exception) {
        mutable.value = TopicAiState(visible = true, position = AiSheetPosition.FULL, title = "读取失败", unsaved = true)
      }
    }
  }

  suspend fun preview(source: AiSource): AiSourcePreview = withContext(compute) {
    val conversationId = state.value.conversationId
    val accountUid = deps.credentials.current()?.uid
    val result = AiSourcePreviewReader(
      readFresh = { params -> ForumReadLimiter.shared.read {
        com.chasel.ng2n.core.api.fetchTopicDetail(deps.client, params.tid, params.page, pid = params.pid, currentAccountFresh = true)
      } },
      rules = {
        val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
        val uid = deps.credentials.current()?.uid
        local + if (uid == null) emptyList() else officialFilterRules(fetchBlockWords(deps.client, uid))
      }, urls = deps.attachmentUrls,
    ).read(AiSourceCoordinate(source.tid, source.pid, source.page, source.part, source.floor))
    if (accountUid != deps.credentials.current()?.uid) return@withContext AiSourcePreview("request_failed")
    if (result.status == "unavailable" && conversationId == state.value.conversationId) {
      mutable.update { it.copy(sourceUnavailable = true) }
      try { save() } catch (_: AiStorageException) { storageFailed() }
    }
    result
  }

  fun retrySave() {
    if (!state.value.unsaved) return
    scope.launch { try { save(); mutable.update { it.copy(unsaved = false) } } catch (_: AiStorageException) { storageFailed() } }
  }

  fun open(params: TopicPageParams, pid: Long?, title: String) {
    job?.cancel(); generation++
    entry = params; selected = pid; history.clear(); tools = null; modelContext = null; prepareEntry = null; personaCovered = false
    conversationKind = if (pid == null) "主题" else "楼层"
    createdAt = System.currentTimeMillis(); run = null; sourceRecords.clear()
    val id = UUID.randomUUID().toString()
    mutable.value = TopicAiState(usage = state.value.usage, visible = true, title = title, entryKind = conversationKind, floorEntry = pid != null, conversationId = id)
    register(id, this)
    send("请简短概览这些发言的主要内容、共识与分歧，并附来源。")
  }
  private fun openEntry(params: TopicPageParams, kind: String, title: String, prepare: suspend () -> TopicContext) {
    job?.cancel(); generation++
    entry = params; selected = null; history.clear(); tools = null; modelContext = null; prepareEntry = prepare; personaCovered = false
    conversationKind = kind; createdAt = System.currentTimeMillis(); run = null; sourceRecords.clear()
    val id = UUID.randomUUID().toString()
    mutable.value = TopicAiState(usage = state.value.usage, visible = true, title = title, entryKind = kind, conversationId = id, allowImages = kind != "个人",
      position = if (kind == "个人") AiSheetPosition.FULL else AiSheetPosition.HALF)
    register(id, this)
    send(if (kind == "个人") "请加载 persona-evidence skill，生成个人发言分析报告，只整理本人明确表达过的内容。" else "请简短概览当前范围的主要讨论，说明实际阅读范围并附来源。")
  }

  fun openPersona(uid: Long, name: String) {
    if (!canAnalyzePersona(uid)) return
    openEntry(TopicPageParams(0, 1, authorId = uid), "个人", "个人分析 · $name") {
      val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
      val account = deps.credentials.current()?.uid
      val official = if (account == null) emptyList() else officialFilterRules(ForumReadLimiter.shared.read { fetchBlockWords(deps.client, account) })
      PersonaHistoryReader({ user, kind, page -> com.chasel.ng2n.core.api.fetchUserTopics(deps.client, user, kind, page, sortByPostDate = true) })
        .load(uid, name, local + official)
    }
  }

  fun openList(topics: List<com.chasel.ng2n.core.api.Topic>, title: String, sort: String,
    rules: List<com.chasel.ng2n.core.local.FilterRule>) {
    val snapshot = topics.toList()
    val filters = rules.toList()
    openEntry(TopicPageParams(0, 1), "列表", "列表概览 · $title · $sort") {
      val uid = deps.credentials.current()?.uid
      val official = if (uid == null) emptyList() else officialFilterRules(ForumReadLimiter.shared.read { fetchBlockWords(deps.client, uid) })
      buildListContext(snapshot, title, sort, filters + official)
    }
  }

  fun openChain(params: TopicPageParams, chain: List<com.chasel.ng2n.core.local.ChainNode>,
    pages: List<com.chasel.ng2n.core.api.TopicDetail>, startFloor: Long?) {
    val nodes = chain.toList()
    val snapshot = pages.toList()
    openEntry(params, "回复链", "回复链 · ${nodes.size} 层 · 从 ${startFloor ?: "当前"} 楼展开") {
      val details = snapshot.toMutableList()
      val first = details.firstOrNull { it.page == 1 && it.floors.any { floor -> floor.lou == 0L } }
        ?: ForumReadLimiter.shared.read { deps.repository.loadDetail(params.copy(page = 1, pid = null, authorId = null)) }.also { details += it }
      for (node in nodes) {
        if (details.any { detail -> (detail.floors + detail.hotReplies).any { it.pid == node.pid } }) continue
        try {
          details += ForumReadLimiter.shared.read { deps.repository.loadDetail(params.copy(page = node.ref?.page?.toInt() ?: 1,
            pid = node.pid.takeIf { it > 0 }, authorId = null)) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
      }
      val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
      val uid = deps.credentials.current()?.uid
      val official = if (uid == null) emptyList() else officialFilterRules(ForumReadLimiter.shared.read { fetchBlockWords(deps.client, uid) })
      buildChainContext(first, details, nodes, local + official, deps.attachmentUrls)
    }
  }

  fun discard() {
    usageJob?.cancel()
    generation++; job?.cancel(); job = null
    entry = null; selected = null; tools = null; modelContext = null; prepareEntry = null; history.clear(); sourceRecords.clear()
    mutable.value = TopicAiState(title = "对话已删除")
  }
  fun position(value: AiSheetPosition) { mutable.update { it.copy(position = value) } }
  fun draft(value: String) {
    mutable.update { it.copy(draft = value) }
    scope.launch { try { save() } catch (_: AiStorageException) { storageFailed() } }
  }
  fun stop() {
    if (!state.value.busy) return
    generation++; job?.cancel(); job = null
    mutable.update { it.copy(busy = false, turns = it.turns.dropLast(1) + it.turns.last().copy(status = "已停止 · 未完成", incomplete = true,
      text = trimIncompleteMarkdown(it.turns.last().text),
      tools = it.turns.last().tools.map { row -> if (row.status == "running") row.copy(status = "cancelled", detail = "已停止，未读取完成") else row })) }
    run = run?.copy(status = "stopped", step = "用户停止")
    scope.launch { try { save() } catch (_: AiStorageException) { storageFailed() } }
  }
  fun decideBudget(more: Boolean) {
    val last = state.value.turns.lastOrNull() ?: return
    if (last.card != "budget" || last.decision != null || state.value.busy) return
    val id = last.analysisId ?: return
    val conversation = state.value.conversationId
    val token = ++generation
    fun valid() = token == generation && state.value.conversationId == conversation
    mutable.update { it.copy(busy = true) }
    val confirmation = scope.launch(start = CoroutineStart.LAZY) {
      try {
        budgets?.decide(id, more, last.addition)
        ensureActive()
        if (!valid()) return@launch
        mutable.update { it.copy(turns = it.turns.dropLast(1) + last.copy(
          decision = if (more) "已追加 ${aiMoney(last.addition)} 额度" else "已到此为止，结果已保存",
          card = null, incomplete = more, status = if (more) "已追加额度" else "已到此为止")) }
        save(finishingDecision = true)
        ensureActive()
        if (!valid()) return@launch
        mutable.update { it.copy(busy = false) }
        if (more) retry()
      } catch (e: CancellationException) { throw e }
      catch (_: AiStorageException) { if (valid()) storageFailed() }
    }
    job = confirmation
    confirmation.start()
  }
  fun retry() {
    if (state.value.busy || state.value.turns.lastOrNull()?.card == "budget" || state.value.turns.lastOrNull()?.status == "已到此为止") return
    val turn = state.value.turns.lastOrNull { it.question != "继续上次分析" } ?: return
    send(turn.question, continuing = true, quickActionId = turn.quickActionId)
  }
  fun quickAction(action: QuickAction) {
    if (action !in BuiltinQuickActions.forEntry(state.value.entryKind, state.value.floorEntry)) return
    send(action.task, quickActionId = action.skillId)
  }
  fun send(question: String = state.value.draft, continuing: Boolean = false, quickActionId: String? = null) {
    if (state.value.busy || state.value.unsaved || question.isBlank() || state.value.turns.lastOrNull()?.card == "budget") return
    val params = entry ?: return
    val token = ++generation
    val prior = history.toList()
    val selectedPid = selected
    val previousAnalysis = if (continuing) state.value.turns.lastOrNull()?.analysisId else null
    val previousRun = if (continuing) run?.takeIf { it.status != "completed" }?.id else null
    run = AiRunEntity(UUID.randomUUID().toString(), state.value.conversationId ?: UUID.randomUUID().toString(), "running", "读取内容", System.currentTimeMillis(), previousRun)
    val persistence = store?.let { AiRunPersistence(it, run!!.conversationId, run!!.id, previousRun) }
    mutable.update { it.copy(busy = true, needsKey = false, draft = "", interruption = null,
      turns = it.turns + AiTurn(if (continuing) "继续上次分析" else question, quickActionId = quickActionId,
        reportExpected = conversationKind == "个人" && quickActionId == null && !personaCovered)) }
    fun turn(update: (AiTurn) -> AiTurn) {
      mutable.update { state ->
        if (token == generation) state.copy(turns = state.turns.dropLast(1) + update(state.turns.last())) else state
      }
    }
    val queueId = run!!.id
    var budget: AiRunBudget? = null
    var savedAt = 0L
    job = scope.launch {
      try {
        store?.initialize()
        save()
        AiExecutionQueue.shared.enter(queueId) { ahead ->
          turn { it.copy(status = if (ahead > 0) "等待执行 · 前面还有 $ahead 个对话" else "读取内容") }
        }
        ensureActive()
        budgets?.let {
          val id = it.begin(state.value.conversationId!!, previousAnalysis)
          val limits = it.limits(conversationKind)
          budget = AiRunBudget(it, id).also { control ->
            control.limits = limits
            control.onRetry = { turn { it.copy(status = "等待重试 · 第 2 次尝试") }; save() }
          }
          turn { turn -> turn.copy(analysisId = id) }
        }
        var context = modelContext
        if (store != null && tools == null && state.value.conversationId != null) {
          context = store.work(state.value.conversationId!!, "context", "current")?.let { Json.decodeFromString<TopicContext>(it) } ?: context
          if (context != null) {
            mutable.update { it.copy(context = context) }
            tools = ForumToolSession(context, { deps.repository.loadDetail(it) }, {
              val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
              val uid = deps.credentials.current()?.uid
              local + if (uid == null) emptyList() else officialFilterRules(fetchBlockWords(deps.client, uid))
            }, deps.attachmentUrls, allowImages = conversationKind != "个人")
          }
        }
        if (context == null && prepareEntry != null) {
          val prepared = withContext(compute) { prepareEntry!!.invoke() }
          context = prepared.copy(imageInput = prepared.image?.let { AiImageReader.read(it) })
          mutable.update { it.copy(context = context, steps = prepared.ranges.map { row -> ReadingStep("${row.label} · ${row.amount}", !row.skipped) },
            readImageUrls = it.readImageUrls + listOfNotNull(prepared.image)) }
        }
        if (conversationKind in listOf("列表", "回复链", "个人") && context == null) {
          turn { it.copy(status = "入口范围不可恢复，请从原内容重新发起分析", incomplete = true) }
          return@launch
        }
        // 一条样本都没有时没有可分析的资料：不请求模型、不计费，保留范围卡让用户重试。
        val personaScope = context?.takeIf { conversationKind == "个人" }
        if (personaScope != null && personaScope.sampleCount == 0) {
          val failed = personaScope.missing.isNotEmpty()
          run = run?.copy(status = if (failed) "failed" else "empty", step = if (failed) "历史读取失败" else "无可访问样本")
          turn { it.copy(status = if (failed) "历史读取失败" else "没有可分析的发言",
            card = if (failed) "READ" else "EMPTY", incomplete = true) }
          return@launch
        }
        if (context != null) {
          modelContext = context
          withContext(compute) { persistence?.saveContext(context) }
          save()
        }
        val key = keys.readKey()
        if (key == null) {
          run = run?.copy(status = "blocked_key")
          mutable.update { it.copy(needsKey = true) }
          turn { it.copy(status = "尚未配置 API Key，请前往 AI 设置", incomplete = true) }
          return@launch
        }
        if (context == null) {
          val firstParams = params.copy(page = 1, pid = null, authorId = null)
          val currentParams = if (selectedPid == null) params
            else params.copy(page = 1, pid = selectedPid.takeIf { it > 0 }, authorId = null)
          val currentScope = when {
            selectedPid != null -> "选中楼层 · pid=$selectedPid"
            params.authorId != null -> "当前页 · 只看用户 ${params.authorId} · 第 ${params.page} 页"
            params.pid != null -> "当前页 · 只看楼层 ${params.pid}"
            else -> "当前页 · 第 ${params.page} 页"
          }
          val labels = if (selectedPid != null) listOf("主楼正文", "选中楼层")
            else listOf("第 1 页", "热门回复") + if (currentParams == firstParams) emptyList() else listOf(currentScope)
          mutable.update { it.copy(steps = labels.map(::ReadingStep) + ReadingStep("图片")) }
          fun done(index: Int, label: String? = null) { if (token == generation) mutable.update { s -> s.copy(steps = s.steps.mapIndexed { i, step -> if (i == index) ReadingStep(label ?: step.label, true) else step }) } }
          val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
          val uid = deps.credentials.current()?.uid
          val official = if (uid == null) emptyList() else officialFilterRules(fetchBlockWords(deps.client, uid))
          run = run?.copy(step = "读取第 1 页与热门回复"); save()
          val first = deps.repository.loadDetail(firstParams)
          done(0)
          if (selectedPid == null) done(1, "热门回复 · ${first.hotReplies.size} 条")
          run = run?.copy(step = "读取$currentScope"); save()
          val current = if (currentParams == firstParams) first else deps.repository.loadDetail(currentParams)
          if (selectedPid != null && (current.floors + current.hotReplies).none { it.pid == selectedPid }) {
            mutable.update { it.copy(steps = it.steps.mapIndexed { index, step ->
              if (index == 1) ReadingStep("选中楼层 · 未读取（已删除或不可访问）") else step
            }) }
            turn { it.copy(status = "选中楼层未读取，无法分析该发言；可能已删除或当前账号无法访问。", incomplete = true) }
            return@launch
          }
          done(labels.lastIndex)
          context = withContext(compute) {
            buildTopicContext(first, current, selectedPid, local + official, deps.attachmentUrls, currentScope).let { built ->
              val visible = built.sources.filter { it.part == "floor" }.map { it.pid }.toSet()
              built.copy(entryKind = conversationKind, ranges = if (selectedPid != null) listOf(
                AiReadingRow("主楼正文", if (built.sources.any { it.floor == 0L }) "1 楼" else "未计入"),
                AiReadingRow("选中楼层", if (built.sources.any { it.pid == selectedPid }) "1 楼" else "未计入"),
                imageReadingRow(built.sources, built.image, "从选中发言选取")) else listOf(
                AiReadingRow("第 1 页", "${first.floors.count { it.pid in visible }} 楼"),
                AiReadingRow("热门回复", "${first.hotReplies.count { it.pid in visible }} 条")) +
                (if (currentParams == firstParams) emptyList() else listOf(AiReadingRow(currentScope, "${current.floors.count { it.pid in visible }} 楼"))) +
                imageReadingRow(built.sources, built.image, if (built.sources.any { it.floor == 0L && built.image in it.images }) "主楼第一张" else "主楼无图，取第 ${current.page} 页第一张"))
            }
          }
          run = run?.copy(step = "读取初始图片"); save()
          context = context.copy(imageInput = context.image?.let { AiImageReader.read(it) })
          mutable.update { it.copy(readImageUrls = it.readImageUrls + listOfNotNull(context.image)) }
          val officialByUid = mutableMapOf(uid to official)
          tools = ForumToolSession(context, { deps.repository.loadDetail(it) }, {
            val currentLocal = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
            val currentUid = deps.credentials.current()?.uid
            currentLocal + if (currentUid == null) emptyList() else officialByUid[currentUid] ?: officialFilterRules(ForumReadLimiter.shared.read { fetchBlockWords(deps.client, currentUid) }).also { officialByUid[currentUid] = it }
          }, deps.attachmentUrls, allowImages = conversationKind != "个人").also { session -> session.seed(firstParams, first); session.seed(currentParams, current) }
          done(labels.size, "图片 ${if (context.image == null) 0 else 1} 张")
          ensureActive()
          mutable.update { it.copy(context = context) }
        }
        modelContext = context
        if (tools == null) tools = ForumToolSession(context, { deps.repository.loadDetail(it) }, {
          val local = deps.settings.localFilterRules.first().mapNotNull { it.toMatchRule() }
          val uid = deps.credentials.current()?.uid
          local + if (uid == null) emptyList() else officialFilterRules(ForumReadLimiter.shared.read { fetchBlockWords(deps.client, uid) })
        }, deps.attachmentUrls, allowImages = conversationKind != "个人")
        withContext(compute) { persistence?.saveContext(context) }
        save()
        val answer = withContext(compute + (persistence ?: kotlin.coroutines.EmptyCoroutineContext) + (budget ?: kotlin.coroutines.EmptyCoroutineContext)) { runtime.runWithTools(key, context, prior, question + (quickActionId?.let { "\n请加载并使用 skill：$it。" } ?: ""), tools, onStart = {
          currentCoroutineContext().ensureActive()
          if (token != generation) throw CancellationException()
          turn { it.copy(status = "正在思考", thinkingStarted = System.nanoTime()) }
          run = run?.copy(step = "生成回答")
          save()
        }, onFrame = frame@{ frame ->
          if (token != generation) return@frame
          if (frame is AiStreamUpdate.Tool && frame.row.status == "unavailable") mutable.update { it.copy(sourceUnavailable = true) }
          when (frame) {
            is AiStreamUpdate.Skills -> turn { it.copy(skillsVersion = frame.version) }
            is AiStreamUpdate.Tool -> turn { old -> old.copy(status = if (frame.row.status == "running") "读取内容" else old.status,
              tools = if (old.tools.any { it.id == frame.row.id }) old.tools.map { if (it.id == frame.row.id) frame.row else it } else old.tools + frame.row) }
            is AiStreamUpdate.Sources -> if (token == generation) {
              modelContext = modelContext?.copy(sources = frame.sources)
              mutable.update { it.copy(context = it.context?.copy(sources = frame.sources), readImageUrls = it.readImageUrls + tools?.readImageUrls.orEmpty()) }
            }
            is AiStreamUpdate.Text -> turn { it.copy(text = if (frame.complete) frame.value else it.text + frame.value, status = "生成回答",
              thoughtSeconds = it.thoughtSeconds ?: it.thinkingStarted?.let { start -> (System.nanoTime() - start) / 1_000_000_000 }) }
          }
          run = run?.copy(step = if (frame is AiStreamUpdate.Tool) "${frame.row.name} ${frame.row.arguments}" else state.value.turns.lastOrNull()?.status ?: "读取内容")
          // 文字按 token 到达，逐帧落库会把 Room 写入压回流式读取；只有增量文字节流保存，其余帧照常立即保存。
          val now = System.nanoTime()
          if (frame !is AiStreamUpdate.Text || frame.complete || now - savedAt >= 400_000_000L) { savedAt = now; save() }
        }) }
        ensureActive()
        if (token == generation) history += answer
        val sampleCount = context.sampleCount
        val report = if (conversationKind == "个人") parsePersonaReport(state.value.turns.last().text, context.sources + tools?.allSources.orEmpty(), sampleCount) else null
        // 已处理数只按初始样本编号计算：技能要求补读的主楼也会分配来源编号，不能算进样本覆盖。
        if (report != null && report.processedSampleCount(sampleCount) >= sampleCount) personaCovered = true
        val reportRequired = conversationKind == "个人" && quickActionId == null && !personaCovered
        val reportIncomplete = reportRequired && (report == null || report.processedSampleCount(sampleCount) < sampleCount)
        turn { it.copy(status = if (reportIncomplete) "个人报告尚未覆盖全部样本，可继续" else "已完成", incomplete = reportIncomplete) }
        run = run?.copy(status = if (reportIncomplete) "incomplete" else "completed", step = state.value.turns.last().status)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: AiStorageException) {
        storageFailed()
      } catch (e: AiBudgetExceeded) {
        run = run?.copy(status = "paused_budget", step = e.message.orEmpty())
        val addition = budgets?.allowance() ?: 0
        turn { it.copy(status = e.message.orEmpty(), incomplete = true, card = if (e.daily) "daily" else "budget", addition = addition) }
      } catch (e: Exception) {
        if (persistence?.failed == true) storageFailed()
        val failure = classifyAiFailure(e, budget?.outputStarted == true || state.value.turns.lastOrNull()?.text?.isNotBlank() == true)
        // 达到执行上限不是失败：已读资料、已生成文字与来源全部保留，继续复用同一分析与检查点。
        val unread = failure != AiFailure.LIMIT && budget != null && budget?.requestId == null
        run = run?.copy(status = if (failure == AiFailure.LIMIT) "limit" else "failed")
        if (failure.settings && token == generation) mutable.update { it.copy(needsKey = true) }
        turn { it.copy(status = when {
          unread -> "内容读取失败"
          failure == AiFailure.LIMIT -> listOfNotNull(failure.title, aiRunLimitDetail(e)).joinToString(" · ")
          else -> failure.title
        }, card = if (unread) "READ" else failure.name, incomplete = true) }
      } finally {
        try { budget?.finish() } catch (_: AiStorageException) { if (token == generation) storageFailed() }
        AiExecutionQueue.shared.leave(queueId)
        if (token == generation && run?.status == "running") run = run?.copy(status = "interrupted")
        if (token == generation) mutable.update { state -> state.copy(busy = false, turns = state.turns.dropLast(1) + state.turns.last().let { last ->
          last.copy(text = trimIncompleteMarkdown(last.text),
            thoughtSeconds = last.thoughtSeconds ?: last.thinkingStarted?.let { start -> (System.nanoTime() - start) / 1_000_000_000 },
            tools = last.tools.map { row -> if (row.status == "running") row.copy(status = "request_failed", detail = "执行中断，未读取完成") else row })
        }) }
        if (token == generation) withContext(NonCancellable) { try { save() } catch (_: AiStorageException) { storageFailed() } }
      }
    }
  }
}
