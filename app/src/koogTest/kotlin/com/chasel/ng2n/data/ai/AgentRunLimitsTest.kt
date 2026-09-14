package com.chasel.ng2n.data.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.chasel.ng2n.core.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunLimitsTest {
  private val runtime = TopicAgentRuntime(DeepSeekClientFactory())
  private class Executor(val flow: (Int) -> Flow<StreamFrame>) : PromptExecutor() {
    var calls = 0
    val prompts = mutableListOf<Prompt>()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("Must stream")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
      currentCoroutineContext()[AiRunBudget]?.sending()
      prompts += prompt
      emitAll(flow(++calls))
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("No moderation")
    override fun close() = Unit
  }

  private fun listContext() = buildListContext(
    listOf(com.chasel.ng2n.core.api.Topic(42, subject = "消费讨论", author = "甲")), "版块", "最新回复")

  private fun session() = ForumToolSession(listContext(), {
    com.chasel.ng2n.core.api.TopicDetail(tid = 42, subject = "消费讨论", attachBase = "",
      floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 0, lou = 0, authorKey = "1", content = "正文证据")))
  }, { emptyList() }, limiter = ForumReadLimiter(0))

  @Test fun completedAnswerIsNotReportedAsALimitEvenWhenTheLoopCounterTripped() {
    assertTrue(answerIsComplete(Message.Assistant("完整回答", ResponseMetaInfo.Empty)))
    assertFalse(answerIsComplete(null))
    assertFalse(answerIsComplete(Message.Assistant("", ResponseMetaInfo.Empty)))
    assertFalse(answerIsComplete(Message.Assistant(
      listOf(MessagePart.Text("读到一半"), MessagePart.Tool.Call("c", "read_topic_page", """{"tid":42}""")),
      ResponseMetaInfo.Empty)))
  }

  @Test fun reachingTheIterationLimitKeepsGeneratedTextAndAllowsContinuing() = runTest {
    val ledger = MemoryAiBudgetRepository()
    val tools = session()
    val streamed = StringBuilder()
    val executor = Executor { flow {
      emit(StreamFrame.TextDelta("先给出部分结论。"))
      emit(StreamFrame.ToolCallComplete("read", "read_topic_page", """{"tid":42,"page":1}"""))
      emit(StreamFrame.End("tool_calls"))
    } }
    val budget = AiRunBudget(ledger, ledger.begin("conversation")).also { it.limits = AiRunLimits(iterations = 3) }
    val failure = runCatching {
      withContext(budget) {
        runtime.runWith(executor, tools.initialContext, emptyList(), "事实核查",
          { frame -> if (frame is StreamFrame.TextDelta) streamed.append(frame.text) }, {}, tools)
      }
    }.exceptionOrNull()
    assertTrue(failure is AiRunLimitReached)
    assertEquals(AiFailure.LIMIT, classifyAiFailure(failure!!, outputStarted = true))
    assertEquals("本次分析已达到执行上限（3 步）", aiRunLimitDetail(failure))
    assertEquals("先给出部分结论。", streamed.toString())

    // 「继续」创建新运行，复用同一预算账本，本次在放开的上限内跑完。
    val answered = Executor { call -> flow {
      if (call == 1) { emit(StreamFrame.ToolCallComplete("read", "read_topic_page", """{"tid":42,"page":1}""")); emit(StreamFrame.End("tool_calls")) }
      else { emit(StreamFrame.TextComplete("补完的完整回答")); emit(StreamFrame.End("stop")) }
    } }
    val resumed = AiRunBudget(ledger, ledger.begin("conversation")).also { it.limits = AiRunLimits() }
    val result = withContext(resumed) {
      runtime.runWith(answered, tools.initialContext, emptyList(), "继续上次分析", {}, {}, tools)
    }
    assertEquals("补完的完整回答", result.textContent())
  }

  @Test fun defaultLimitsCoverAFactCheckShapedLoopThatTheOldEightStepCapKilled() = runTest {
    val tools = session()
    // 三次技能读取 + 两次搜索 + 一次论坛读取 + 收尾，正是验收里必然中断的形状。
    val executor = Executor { call -> flow {
      if (call <= 6) {
        emit(StreamFrame.ToolCallComplete("t$call", "read_topic_page", """{"tid":42,"page":1,"offset":$call}"""))
        emit(StreamFrame.End("tool_calls"))
      } else { emit(StreamFrame.TextComplete("核查结论")); emit(StreamFrame.End("stop")) }
    } }
    val ledger = MemoryAiBudgetRepository().apply { allowanceValue = 5_000_000 }
    val budget = AiRunBudget(ledger, ledger.begin("conversation"))
    val result = withContext(budget) {
      runtime.runWith(executor, tools.initialContext, emptyList(), "事实核查", {}, {}, tools)
    }
    assertEquals("核查结论", result.textContent())
    assertEquals(7, executor.calls)
    assertTrue(AiRunLimits().iterations >= 2 * executor.calls)
  }

  @Test fun toolExecutionCapAndRepeatedReadsStopFurtherToolsWithoutEndingTheRun() = runTest {
    val ledger = MemoryAiBudgetRepository()
    val capped = AiRunBudget(ledger, ledger.begin("a")).also { it.limits = AiRunLimits(toolCalls = 2) }
    capped.beforeTool("read_topic_page", "read_topic_page {\"page\":1}")
    capped.beforeTool("read_topic_page", "read_topic_page {\"page\":2}")
    assertNull(capped.exhausted)
    capped.beforeTool("read_topic_page", "read_topic_page {\"page\":3}")
    assertEquals("本次分析的工具执行次数已达上限（2 次）", capped.exhausted)

    val stalled = AiRunBudget(ledger, ledger.begin("b")).also { it.limits = AiRunLimits(repeats = 2) }
    stalled.beforeTool("read_floor", "read_floor {\"pid\":7}")
    stalled.beforeTool("read_floor", "read_floor {\"pid\":7}")
    stalled.beforeTool("read_floor", "read_floor {\"pid\":7}")
    assertEquals("连续 2 次重复读取同一资料，没有新增证据", stalled.exhausted)
    // 标记之后论坛工具一律拒绝执行，不再发起任何读取。
    val tools = ForumToolSession(listContext(), { error("达到上限后不得再读取论坛") }, { emptyList() }, limiter = ForumReadLimiter(0))
    val refused = withContext(stalled) { tools.execute("read_floor", ForumToolArgs(tid = 42, pid = 7)) }
    assertEquals("limit_reached", refused["status"]?.jsonPrimitive?.content)
  }

  @Test fun toolCapReachedMidRunStillWritesTheAnswerInTheSameRun() = runTest {
    val ledger = MemoryAiBudgetRepository().apply { allowanceValue = 5_000_000 }
    val tools = session()
    val budget = AiRunBudget(ledger, ledger.begin("persona")).also { it.limits = AiRunLimits(toolCalls = 3) }
    val answer = "已读资料有限，先给出可确认的结论"
    val executor = Executor { call -> flow {
      if (call <= 5) {
        emit(StreamFrame.ToolCallComplete("t$call", "read_topic_page", """{"tid":42,"page":$call}"""))
        emit(StreamFrame.End("tool_calls"))
      } else { emit(StreamFrame.TextComplete(answer)); emit(StreamFrame.End("stop")) }
    } }
    val rows = mutableListOf<ToolCallRow>()
    val result = withContext(budget) {
      runtime.runWith(executor, tools.initialContext, emptyList(), "事实核查", {}, {}, tools,
        onUpdate = { update -> if (update is AiStreamUpdate.Tool && update.row.status != "running") rows += update.row })
    }
    assertEquals(answer, result.textContent())
    assertEquals("本次分析的工具执行次数已达上限（3 次）", budget.exhausted)
    // 第 4 次调用被拒绝执行，第 5 次不再进入工具节点，收尾请求在同一次运行内写完，用户不必再点「继续」。
    assertEquals(listOf("ok", "ok", "ok", "limit_reached"), rows.map { it.status })
    assertEquals(6, executor.calls)
    assertTrue(executor.prompts.last().messages.any { it.textContent().contains("不能再调用任何工具") })
  }

  @Test fun personaReportArrivesInOneRunWhenSeveralFloorReadsFail() = runTest {
    val posts = (1L..6L).map { com.chasel.ng2n.core.api.Topic(tid = it, subject = "主题 $it", author = "本人",
      authorId = 7, postedAt = 1_700_000_000 + it) }
    val context = buildPersonaContext(posts, "本人")
    assertEquals(6, context.sampleCount)
    val attempts = mutableListOf<Long>()
    val tools = ForumToolSession(context, { params ->
      attempts += params.tid
      throw com.chasel.ng2n.core.net.NgaError(com.chasel.ng2n.core.net.NgaErrorKind.NETWORK, "offline")
    }, { emptyList() }, limiter = ForumReadLimiter(0))
    val report = """{"overview":"6 条主题标题样本，主楼正文未读取","processedSourceIds":["s1","s2","s3","s4","s5","s6"],""" +
      """"interests":[{"title":"话题偏好","body":"以主题形式提到该话题的发言有","mentions":2,"bodyTail":"，主楼正文未读取。",""" +
      """"evidence":["s1","s2"],"counter":[]}],"positions":[],"judgment":"正文缺失，只按标题判断",""" +
      """"timeline":[],"boundary":"$PERSONA_BOUNDARY"}"""
    // 模型逐条补读主楼，每条都失败后又重试一次，最后用标题写完报告。
    val targets = (1L..6L).flatMap { listOf(it, it) }
    val executor = Executor { call -> flow {
      val target = targets.getOrNull(call - 1)
      if (target != null) {
        emit(StreamFrame.ToolCallComplete("floor$call", "read_floor", """{"tid":$target,"pid":0}"""))
        emit(StreamFrame.End("tool_calls"))
      } else { emit(StreamFrame.TextComplete(report)); emit(StreamFrame.End("stop")) }
    } }
    val ledger = MemoryAiBudgetRepository().apply { allowanceValue = 5_000_000; limitsValue = runLimitsFor("long", "个人") }
    val budget = AiRunBudget(ledger, ledger.begin("persona")).also { it.limits = ledger.limitsValue }
    val rows = mutableListOf<ToolCallRow>()
    val result = withContext(budget) {
      runtime.runWith(executor, context, emptyList(), "生成个人发言分析报告", {}, {}, tools,
        onUpdate = { update -> if (update is AiStreamUpdate.Tool && update.row.status != "running") rows += update.row })
    }
    assertEquals(13, executor.calls)
    assertNull(budget.exhausted)
    // 失败的补读不再重复请求论坛，重复调用直接拿到上次的失败原因。
    assertEquals((1L..6L).toList(), attempts)
    assertEquals(12, rows.size)
    assertTrue(rows.all { it.status == "request_failed" })
    assertEquals(6, rows.count { it.detail.contains("未重复请求") })
    val parsed = parsePersonaReport(result.textContent(), context.sources, context.sampleCount)
    assertEquals(6, parsed?.processedSampleCount(context.sampleCount))
  }

  @Test fun shortAllowanceStartsAFirstOverviewInsteadOfExceedingItsOwnReservation() = runTest {
    // 一页中文楼层的实际规模：按 UTF-8 字节估算时首个请求的保守预留就超过 ¥0.2。
    val page = com.chasel.ng2n.core.api.TopicDetail(tid = 42, subject = "主题标题", attachBase = "",
      floors = (0L..19L).map { com.chasel.ng2n.core.api.Floor(pid = it, lou = it, authorKey = "1", content = "讨论正文内容".repeat(100)) })
    val context = buildTopicContext(page, page)
    assertTrue(context.material().length > 10_000)
    val tools = ForumToolSession(context, { page }, { emptyList() }, limiter = ForumReadLimiter(0))
    val ledger = MemoryAiBudgetRepository().apply {
      allowanceValue = 200_000
      limitsValue = runLimitsFor("short")
    }
    val executor = Executor { flow { emit(StreamFrame.TextComplete("短概览")); emit(StreamFrame.End("stop")) } }
    val budget = AiRunBudget(ledger, ledger.begin("conversation")).also { it.limits = ledger.limitsValue }
    val result = withContext(budget) { runtime.runWith(executor, context, emptyList(), "概览", {}, {}, tools) }
    assertEquals("短概览", result.textContent())
    val reserved = ledger.books.value.requests.single().reserved
    assertTrue("短问答档首个请求预留 $reserved 超过额度", reserved < 200_000)
  }

  @Test fun personaFirstRoundFinishesSkillHistoryAndFloorReadsWithinTheAllowanceLimits() = runTest {
    val base = java.nio.file.Files.createTempDirectory("persona-skills").toFile()
    try {
      val assets = java.io.File("src/main/assets")
      val root = releaseSkills(base, "1", { java.io.File(assets, it).list()?.toList().orEmpty() },
        { java.io.File(assets, it).readBytes() })
      val catalog = SkillCatalog.create(root)
      val posts = (1L..30L).map { com.chasel.ng2n.core.api.Topic(tid = it, subject = "主题 $it", author = "本人",
        authorId = 7, postedAt = 1_700_000_000 + it,
        reply = if (it % 2 == 0L) com.chasel.ng2n.core.api.TopicReply(it * 10, "发言内容 $it", it) else null) }
      val context = buildPersonaContext(posts, "本人")
      assertEquals(30, context.sampleCount)
      val tools = ForumToolSession(context, { params -> com.chasel.ng2n.core.api.TopicDetail(tid = params.tid,
        subject = "主题 ${params.tid}", attachBase = "", floors = listOf(
          com.chasel.ng2n.core.api.Floor(pid = 0, lou = 0, authorId = 7, authorKey = "7", content = "主楼正文 ${params.tid}"))) },
        { emptyList() }, limiter = ForumReadLimiter(0))
      val report = """{"overview":"覆盖全部样本","processedSourceIds":[${(1..30).joinToString { "\"s$it\"" }}],""" +
        """"interests":[],"positions":[],"judgment":"证据有限","timeline":[],"boundary":"只整理本人明确表达过的内容"}"""
      // 个人首轮的真实形状：技能正文 + 一次历史续读 + 三次补读主楼 + 收尾，旧的 8 步上限必然打断。
      val executor = Executor { call -> flow {
        when (call) {
          1 -> emit(StreamFrame.ToolCallComplete("skill", "__read_file__",
            """{"path":"${java.io.File(root, "persona-evidence/SKILL.md").absolutePath}"}"""))
          2 -> emit(StreamFrame.ToolCallComplete("history", "read_user_history", """{"offset":0}"""))
          in 3..5 -> emit(StreamFrame.ToolCallComplete("floor$call", "read_floor", """{"tid":${call - 2},"pid":0}"""))
          else -> emit(StreamFrame.TextComplete(report))
        }
        emit(StreamFrame.End(if (call <= 5) "tool_calls" else "stop"))
      } }
      val ledger = MemoryAiBudgetRepository().apply { allowanceValue = 5_000_000; limitsValue = runLimitsFor("long") }
      val budget = AiRunBudget(ledger, ledger.begin("persona")).also { it.limits = ledger.limitsValue }
      val rows = mutableListOf<ToolCallRow>()
      val result = withContext(budget) {
        runtime.runWith(executor, context, emptyList(), "生成个人发言分析报告", {}, {}, tools,
          onUpdate = { if (it is AiStreamUpdate.Tool && it.row.status == "ok") rows += it.row }, catalog = catalog)
      }
      assertEquals(report, result.textContent())
      assertEquals(6, executor.calls)
      assertEquals(listOf("__read_file__", "read_user_history", "read_floor", "read_floor", "read_floor"), rows.map { it.name })
      assertTrue("旧的 8 步上限装不下个人首轮", 8 < 2 * executor.calls)
      assertTrue(ledger.limitsValue.iterations >= 2 * executor.calls)
      assertTrue(AiRunLimits().iterations >= 2 * executor.calls)
    } finally { base.deleteRecursively() }
  }

  @Test fun generationCutOffByTheOutputCeilingIsALimitAndPersonaGetsAReportSizedBudget() = runTest {
    val limits = runLimitsFor("long", "个人")
    // 个人报告是一整块 JSON：正文预算按报告体量单独估算，思考另计，两者之和才是下发与预留的上限。
    assertEquals(PERSONA_REPORT_OUTPUT_TOKENS, limits.outputTokens)
    assertEquals(PERSONA_REPORT_THINKING_TOKENS, limits.thinkingTokens)
    assertEquals(limits.outputTokens + limits.thinkingTokens, limits.maxTokens)
    assertTrue(limits.maxTokens > runLimitsFor("long").maxTokens)

    val ledger = MemoryAiBudgetRepository().apply { allowanceValue = 5_000_000 }
    val tools = session()
    val streamed = StringBuilder()
    val truncated = """{"overview":"报告写到一半"""
    val executor = Executor { flow { emit(StreamFrame.TextDelta(truncated)); emit(StreamFrame.End("length")) } }
    val budget = AiRunBudget(ledger, ledger.begin("persona")).also { it.limits = limits }
    val failure = runCatching {
      withContext(budget) {
        runtime.runWith(executor, tools.initialContext, emptyList(), "生成个人发言分析报告",
          { frame -> if (frame is StreamFrame.TextDelta) streamed.append(frame.text) }, {}, tools)
      }
    }.exceptionOrNull()
    assertTrue(failure is AiRunLimitReached)
    assertEquals(AiFailure.LIMIT, classifyAiFailure(failure!!, outputStarted = true))
    assertEquals("回答达到本次输出上限（${limits.maxTokens} token，含思考），尚未写完", aiRunLimitDetail(failure))
    assertEquals(truncated, streamed.toString())
    // 预留按本次实际下发的上限计算，不再回落到档位默认输出。
    assertTrue(ledger.books.value.requests.single().reserved >= AiPrice().cost(0, limits.maxTokens.toLong()))
  }

  @Test fun webToolExecutionsAreRecordedOnTheRequestSoBothUsageViewsAgree() = runTest {
    val ledger = MemoryAiBudgetRepository()
    val budget = AiRunBudget(ledger, ledger.begin("conversation"))
    budget.reserve(1_000, 0)
    budget.beforeTool("search_web", "search_web {\"query\":\"a\"}")
    budget.beforeTool("read_webpage", "read_webpage {\"url\":\"https://example.com\"}")
    budget.beforeTool("read_topic_page", "read_topic_page {\"tid\":42}")
    assertEquals(2, ledger.books.value.requests.single().web)
  }
}
