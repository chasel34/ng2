package com.chasel.ng2n.data.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.chasel.ng2n.core.ai.TopicContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TopicAgentRuntimeTest {
  private val context = TopicContext(emptyList(), null, 0, listOf(1))
  private val runtime = TopicAgentRuntime(DeepSeekClientFactory())
  private class Executor(val hasTools: Boolean = false, val flow: (Prompt) -> Flow<StreamFrame>) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("Must stream")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
      assertEquals("deepseek-flash", model.id)
      assertEquals(hasTools, tools.isNotEmpty())
      prompts += prompt
      return kotlinx.coroutines.flow.flow {
        currentCoroutineContext()[AiRunBudget]?.sending()
        emitAll(flow(prompt))
      }
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("No moderation")
    override fun close() = Unit
  }
  @Test fun listOverviewFollowupCanReadTopicBodyThroughTheAgentToolLoop() = runTest {
    val initial = com.chasel.ng2n.core.ai.buildListContext(listOf(com.chasel.ng2n.core.api.Topic(42, subject = "消费讨论", author = "甲")), "版块", "最新回复")
    var reads = 0
    val tools = ForumToolSession(initial, { params ->
      reads++; assertEquals(42L, params.tid)
      com.chasel.ng2n.core.api.TopicDetail(tid = 42, subject = "消费讨论", attachBase = "",
        floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 0, lou = 0, authorKey = "1", content = "正文证据：客流增加")))
    }, { emptyList() }, limiter = ForumReadLimiter(0))
    val overview = Executor(true) { flowOf(StreamFrame.TextComplete("列表在讨论消费。"), StreamFrame.End("stop")) }
    val answer = runtime.runWith(overview, initial, emptyList(), "概览", {}, {}, tools)
    assertEquals(0, reads)
    assertFalse(overview.prompts.single().messages.toString().contains("正文证据"))
    var calls = 0
    val followup = Executor(true) { prompt -> flow {
      if (calls++ == 0) emit(StreamFrame.ToolCallComplete("body", "read_topic_page", """{"tid":42,"page":1}"""))
      else {
        assertTrue(prompt.messages.toString().contains("正文证据：客流增加"))
        emit(StreamFrame.TextComplete("读到主楼说客流增加。[[s2]]"))
      }
      emit(StreamFrame.End(if (calls == 1) "tool_calls" else "stop"))
    } }
    val result = runtime.runWith(followup, initial, listOf(AiExchange("概览", answer)), "深入消费讨论，读取正文", {}, {}, tools)
    assertEquals(1, reads)
    assertEquals(2, calls)
    assertTrue(result.textContent().contains("[[s2]]"))
    assertEquals(listOf("summary", "floor"), tools.allSources.map { it.part })
  }

  @Test fun fullPersonaHistoryIsCoveredWithinTheAgentIterationLimit() = runTest {
    val posts = (1L..com.chasel.ng2n.core.ai.PERSONA_SAMPLE_LIMIT).map {
      com.chasel.ng2n.core.api.Topic(tid = it, subject = "标题", author = "本人", authorId = 7, postedAt = it,
        reply = com.chasel.ng2n.core.api.TopicReply(it * 10, "字".repeat(130), it))
    }
    val initial = com.chasel.ng2n.core.ai.buildPersonaContext(posts, "本人")
    assertEquals(com.chasel.ng2n.core.ai.PERSONA_SAMPLE_LIMIT, initial.sampleCount)
    assertTrue(initial.material().length > com.chasel.ng2n.core.ai.PERSONA_INLINE_LIMIT)
    val tools = ForumToolSession(initial, { params ->
      com.chasel.ng2n.core.api.TopicDetail(tid = params.tid, subject = "标题", attachBase = "",
        floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 0, lou = 0, authorKey = "1", content = "主楼正文")))
    }, { emptyList() }, limiter = ForumReadLimiter(0))
    val report = """{"overview":"已处理全部样本","processedSourceIds":[${(1..initial.sampleCount).joinToString(",") { "\"s$it\"" }}],""" +
      """"interests":[],"positions":[],"judgment":"证据有限","timeline":[],"boundary":"仅覆盖已处理样本"}"""
    var calls = 0
    var seen = ""
    val executor = Executor(true) { prompt ->
      calls++
      seen += prompt.messages.toString()
      flow {
        when (calls) {
          // 真实运行的首轮先读技能正文，这里用一次补读主楼占住同样的循环预算。
          1 -> { emit(StreamFrame.ToolCallComplete("f", "read_floor", """{"tid":1,"pid":0}""")); emit(StreamFrame.End("tool_calls")) }
          2 -> { emit(StreamFrame.ToolCallComplete("h", "read_user_history",
            """{"offset":${com.chasel.ng2n.core.ai.PERSONA_INLINE_LIMIT}}""")); emit(StreamFrame.End("tool_calls")) }
          else -> { emit(StreamFrame.TextComplete(report)); emit(StreamFrame.End("stop")) }
        }
      }
    }
    val answer = runtime.runWith(executor, initial, emptyList(), "生成个人分析报告", {}, {}, tools)
    assertEquals(3, calls)
    assertFalse("剩余样本必须一次续读取回", seen.contains("nextOffset"))
    assertTrue(seen.contains("来源 s${initial.sampleCount}，"))
    val parsed = com.chasel.ng2n.core.ai.parsePersonaReport(answer.textContent(), initial.sources, initial.sampleCount)
    assertEquals(initial.sampleCount, parsed!!.processedSourceIds.size)
  }

  @Test fun exhaustedAllowanceStopsBeforeExecutorAndCanResumeAfterExplicitAddition() = runTest {
    val budgets = MemoryAiBudgetRepository().also { it.allowanceValue = 1 }
    val id = budgets.begin("conversation")
    val executor = Executor { flowOf(StreamFrame.TextDelta("回答"), StreamFrame.TextComplete("回答"), StreamFrame.End("stop")) }
    val error = runCatching { withContext(AiRunBudget(budgets, id)) { runtime.runWith(executor, context, emptyList(), "概览", {}, {}) } }.exceptionOrNull()
    assertTrue(error.toString(), error is com.chasel.ng2n.core.ai.AiBudgetExceeded)
    assertTrue(executor.prompts.isEmpty())
    budgets.decide(id, true, 50_000)
    withContext(AiRunBudget(budgets, id)) { runtime.runWith(executor, context, emptyList(), "概览", {}, {}) }
    assertEquals(1, executor.prompts.size)
    assertEquals("pending_verification", budgets.books.value.requests.single().status)
  }
  @Test fun eventHandlerSettlesUsageIncludingReasoningWithoutAddingItAgain() = runTest {
    val budgets = MemoryAiBudgetRepository()
    val id = budgets.begin("conversation")
    val executor = Executor { flowOf(StreamFrame.TextDelta("回答"), StreamFrame.TextComplete("回答"),
      StreamFrame.End("stop", ai.koog.prompt.message.ResponseMetaInfo(kotlin.time.Clock.System.now(), inputTokensCount = 100, outputTokensCount = 20, totalTokensCount = 120))) }
    withContext(AiRunBudget(budgets, id)) { runtime.runWith(executor, context, emptyList(), "概览", {}, {}) }
    val usage = budgets.books.value.requests.single()
    assertEquals("settled", usage.status)
    assertEquals(20L, usage.output)
    assertEquals(com.chasel.ng2n.core.ai.AiPrice().cost(100, 20), usage.cost)
  }
  @Test fun onStartFailureReleasesReservationWithoutCallingExecutor() = unsentReservation(false)
  @Test fun cancellationBeforeHttpReleasesReservationWithoutCallingExecutor() = unsentReservation(true)
  private fun unsentReservation(cancel: Boolean) = runTest {
    val budgets = MemoryAiBudgetRepository()
    val id = budgets.begin("conversation")
    val executor = Executor { error("HTTP must not start") }
    val started = CompletableDeferred<Unit>()
    val task = launch {
      runCatching { withContext(AiRunBudget(budgets, id)) {
        runtime.runWith(executor, context, emptyList(), "概览", {}, {
          started.complete(Unit)
          if (cancel) awaitCancellation() else throw AiStorageException()
        })
      } }
    }
    started.await()
    if (cancel) task.cancel()
    task.join()
    assertTrue(executor.prompts.isEmpty())
    val record = budgets.books.value.requests.single()
    assertEquals("not_sent", record.status)
    assertEquals(0L, record.charged)
    val restarted = kotlinx.serialization.json.Json.decodeFromString<com.chasel.ng2n.core.ai.AiBudgetBook>(
      kotlinx.serialization.json.Json.encodeToString(com.chasel.ng2n.core.ai.AiBudgetBook.serializer(), budgets.books.value)).pending()
    assertEquals(0L, restarted.requests.single().charged)
  }
  @Test fun eventHandlerStreamsBeforeCompletionAndFollowupRetainsProtocolHistory() = runTest {
    val seen = mutableListOf<StreamFrame>()
    var starts = 0
    val executor = Executor { flow {
      emit(StreamFrame.ReasoningDelta(text = "reason"))
      emit(StreamFrame.ReasoningComplete(null, listOf("reason")))
      emit(StreamFrame.TextDelta("回答"))
      delay(100)
      emit(StreamFrame.TextComplete("回答"))
      emit(StreamFrame.End("stop"))
    } }
    val task = async { runtime.runWith(executor, context, emptyList(), "概览", { seen += it }, { starts++ }) }
    runCurrent()
    assertTrue(seen.any { it is StreamFrame.TextDelta })
    assertFalse(task.isCompleted)
    advanceUntilIdle()
    val answer = task.await()
    assertEquals("回答", answer.textContent())
    runtime.runWith(executor, context, listOf(AiExchange("概览", answer)), "追问", {}, {})
    assertEquals(2, executor.prompts.size)
    val messages = executor.prompts.last().messages
    assertEquals(1, messages.count { it.textContent() == "概览" })
    assertTrue(messages.contains(answer))
    assertEquals("追问", messages.last().textContent())
    assertEquals(1, starts)
  }
  @Test fun cancellationEndsActiveRequestAndNeverAutomaticallyRestarts() = runTest {
    var cancelled = false
    val executor = Executor { flow {
      try { emit(StreamFrame.TextDelta("部分文字")); awaitCancellation() } finally { cancelled = true }
    } }
    val job = launch { runtime.runWith(executor, context, emptyList(), "概览", {}, {}) }
    runCurrent()
    job.cancelAndJoin()
    advanceUntilIdle()
    assertTrue(cancelled)
    assertEquals(1, executor.prompts.size)
  }
  @Test fun brokenStreamAndTruncatedToolArgumentsCannotBeCompletedOrExecuted() = runTest {
    val executor = Executor { flowOf(StreamFrame.ToolCallDelta("x", "read_page", "{\"page\":")) }
    val failure = runCatching { runtime.runWith(executor, context, emptyList(), "概览", {}, {}) }
    assertTrue(failure.isFailure)
    assertEquals(1, executor.prompts.size)
  }
  @Test fun outputLimitRetainsFramesButMarksRunIncomplete() = runTest {
    val executor = Executor { flowOf(StreamFrame.TextDelta("草稿"), StreamFrame.TextComplete("草稿"), StreamFrame.End("length")) }
    val seen = mutableListOf<StreamFrame>()
    assertTrue(runCatching { runtime.runWith(executor, context, emptyList(), "概览", { seen += it }, {}) }.isFailure)
    assertTrue(seen.any { it is StreamFrame.TextDelta })
  }
  @Test fun deepSeekStreamingSerializesOneImageAndReturnsReasoningAndText() = runTest {
    mockwebserver3.MockWebServer().use { server ->
      server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      val client = DeepSeekClientFactory().create("offline-key",
        ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings(baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()))
      val executor = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)
      val chunks = listOf(
        """{"id":"x","object":"chat.completion.chunk","system_fingerprint":"offline","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"思考"},"finish_reason":null}]}""",
        """{"id":"x","object":"chat.completion.chunk","system_fingerprint":"offline","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"回答[[s1]]"},"finish_reason":null}]}""",
        """{"id":"x","object":"chat.completion.chunk","system_fingerprint":"offline","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}""",
        "[DONE]",
      )
      server.enqueue(mockwebserver3.MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body(chunks.joinToString("") { "data: $it\n\n" }).build())
      try {
        val image = "data:image/jpeg;base64,b2ZmbGluZQ=="
        val seen = mutableListOf<StreamFrame>()
        val result = runtime.runWith(executor, context.copy(image = "https://example.org/image.jpg", imageInput = image),
          emptyList(), "概览", { seen += it }, {})
        assertEquals("回答[[s1]]", result.textContent())
        assertTrue(seen.any { it is StreamFrame.ReasoningDelta })
        val request = server.takeRequest()
        val body = checkNotNull(request.body).utf8()
        assertTrue(body.contains("image_url"))
        assertEquals(1, Regex("data:image/jpeg;base64").findAll(body).count())
        assertTrue(body.contains("deepseek-flash"))
        assertTrue(body.contains("thinking"))
        assertNull(request.headers["Cookie"])
      } finally { executor.close() }
    }
  }

  @Test fun forumToolsAddCitationsAndImageUserMessageAndKeepCompleteHistory() = runTest {
    val updates = mutableListOf<AiStreamUpdate>()
    val page = com.chasel.ng2n.core.api.TopicDetail(tid = 10, subject = "主题", attachBase = "",
      floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 20, lou = 1, authorKey = "a",
        content = "图示[img]https://example.org/a.jpg[/img]")))
    var reads = 0
    val session = ForumToolSession(context, { reads++; page }, { emptyList() },
      imageReader = { "data:image/jpeg;base64,YQ==" }, limiter = ForumReadLimiter(0))
    var request = 0
    val executor = Executor(hasTools = true) { flow {
      when (request++) {
        0 -> emit(StreamFrame.ToolCallComplete("page", "list_images", """{"tid":10,"pid":20}"""))
        1 -> emit(StreamFrame.ToolCallComplete("image", "read_image", """{"imageId":"s1:image1"}"""))
        else -> emit(StreamFrame.TextComplete("图片内容[[s1]]"))
      }
      emit(StreamFrame.End(if (request <= 2) "tool_calls" else "stop"))
    } }
    var protocol: List<Message> = emptyList()
    val answer = runtime.runWith(executor, context, emptyList(), "看图", {}, {}, session, { updates += it }, { protocol = it })
    assertEquals("图片内容[[s1]]", answer.textContent())
    assertEquals(1, reads)
    assertEquals(2, updates.filterIsInstance<AiStreamUpdate.Tool>().count { it.row.status == "running" })
    assertEquals(2, updates.filterIsInstance<AiStreamUpdate.Tool>().count { it.row.status == "ok" })
    assertEquals("s1", updates.filterIsInstance<AiStreamUpdate.Sources>().last().sources.single().id)
    val messages = executor.prompts.last().messages
    val results = messages.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>()
    assertEquals(listOf("page", "image"), results.map { it.id })
    assertFalse(results.any { it.output.contains("base64") })
    assertTrue(messages.filterIsInstance<Message.User>().any { it.textContent().contains("工具读取的图片资料 s1:image1") && it.parts.size == 2 })
    runtime.runWith(executor, context, listOf(AiExchange("看图", answer, protocol)), "继续", {}, {}, session)
    val continued = executor.prompts.last().messages
    assertEquals(1, continued.count { it.textContent() == "看图" })
    assertEquals(2, continued.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Call>().size)
    assertEquals(2, continued.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().size)
  }

  @Test fun malformedTypedToolArgumentsReturnStructuredErrorWithoutReading() = runTest {
    var calls = 0
    var request = 0
    val session = ForumToolSession(context, { calls++; error("Must not read") }, { emptyList() })
    val executor = Executor(hasTools = true) { flow {
      if (request++ == 0) emit(StreamFrame.ToolCallComplete("bad", "read_topic_page", """{"tid":10,"page":"invalid"}"""))
      else emit(StreamFrame.TextComplete("参数错误"))
      emit(StreamFrame.End(if (request == 1) "tool_calls" else "stop"))
    } }
    runtime.runWith(executor, context, emptyList(), "读取", {}, {}, session)
    assertEquals(0, calls)
    val result = executor.prompts.last().messages.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().single()
    assertTrue(result.output.contains("invalid_parameters"))
  }

  @Test fun toolRowsGetReadableNamesArgumentsAndStatusText() {
    val registered = listOf("__read_file__", "__list_directory__", "read_topic_page")
    assertEquals("__list_directory__", canonicalToolName("__list_directory", registered))
    assertEquals("__read_file__", canonicalToolName("read_file", registered))
    assertEquals("read_topic_page", canonicalToolName("read_topic_page", registered))
    assertEquals("search_web", canonicalToolName("search_web", registered))
    assertEquals("事实核查 · SKILL.md", toolArgumentLabel("__read_file__", """{"path":"/files/ai-skills/5-3/fact-check/SKILL.md"}"""))
    assertEquals("事实核查 · references", toolArgumentLabel("__list_directory__", """{"absolutePath":"/files/ai-skills/5-3/fact-check/references","depth":2}"""))
    assertEquals("事实核查", toolArgumentLabel("__list_directory", """{"absolutePath":"/files/ai-skills/5-3/fact-check","depth":2}"""))
    assertEquals("内置技能", toolArgumentLabel("__list_directory__", """{"absolutePath":"/files/ai-skills/5-3","depth":1}"""))
    assertEquals("""{"tid":42}""", toolArgumentLabel("read_topic_page", """{"tid":42}"""))
    assertEquals("成功", toolStatusLabel("ok"))
    assertEquals("已删除或不可访问", toolStatusLabel("unavailable"))
    assertEquals("客户端接口", toolOriginLabel("NATIVE"))
    assertEquals("本地缓存", toolOriginLabel("CACHE"))
  }

  @Test fun hallucinatedFrameworkToolNameStillReachesTheRegisteredTool() = runTest {
    val rows = mutableListOf<ToolCallRow>()
    val session = ForumToolSession(context, { error("no forum read") }, { emptyList() }, limiter = ForumReadLimiter(0))
    var calls = 0
    val executor = Executor(true) { flow {
      if (calls++ == 0) emit(StreamFrame.ToolCallComplete("call", "read_topic_page", """{"tid":42,"page":1}"""))
      else emit(StreamFrame.TextComplete("好"))
      emit(StreamFrame.End(if (calls == 1) "tool_calls" else "stop"))
    } }
    runtime.runWith(executor, context, emptyList(), "读取", {}, {}, session,
      onUpdate = { if (it is AiStreamUpdate.Tool) rows += it.row })
    assertEquals("read_topic_page", rows.first().name)
  }
}
