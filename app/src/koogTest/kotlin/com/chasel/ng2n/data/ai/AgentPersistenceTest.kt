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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentPersistenceTest {
  private val context = TopicContext(emptyList(), null, 0, listOf(1))
  private val runtime = TopicAgentRuntime(DeepSeekClientFactory())
  private class Executor(val block: (Int) -> Flow<StreamFrame>) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("stream only")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
      prompts += prompt
      return block(prompts.size)
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
    override fun close() = Unit
  }
  @Test fun checkpointResumesAfterSuccessfulToolsWithoutRepeatingInputOrRead() = runTest {
    val store = MemoryAiConversationStore()
    val page = com.chasel.ng2n.core.api.TopicDetail(tid = 10, subject = "测试", attachBase = "", page = 1, totalPages = 1)
    var reads = 0
    fun tools() = ForumToolSession(context, { reads++; page }, { emptyList() }, limiter = ForumReadLimiter(0))
    val first = Executor { number -> flow {
      if (number == 1) {
        emit(StreamFrame.ToolCallComplete("page", "read_topic_page", """{"tid":10}"""))
        emit(StreamFrame.End("tool_calls"))
      } else { emit(StreamFrame.TextDelta("部分回答")); error("stream disconnected") }
    } }
    val failed = runCatching { withContext(AiRunPersistence(store, "conversation", "first")) {
      runtime.runWith(first, context, emptyList(), "概览", {}, {}, tools())
    } }
    assertTrue(failed.isFailure)
    assertEquals(1, reads)
    assertNotNull(store.working[Triple("conversation", "checkpoint", "first")])
    val second = Executor { flowOf(StreamFrame.TextComplete("恢复回答"), StreamFrame.End("stop")) }
    val result = withContext(AiRunPersistence(store, "conversation", "second", "first")) {
      runtime.runWith(second, context, emptyList(), "概览", {}, {}, tools())
    }
    assertEquals("恢复回答", result.textContent())
    assertEquals(1, reads)
    assertEquals(1, second.prompts.size)
    assertEquals(1, second.prompts.single().messages.count { it.textContent() == "概览" })
    assertEquals(1, second.prompts.single().messages.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().size)
    val memory = AiRunPersistence(store, "conversation", "third").chatMemory.load("unrelated-koog-run-id")
    assertTrue(memory.any { it.textContent() == "恢复回答" })
    assertTrue(AiRunPersistence(store, "different-conversation", "third").chatMemory.load("conversation").isEmpty())
    withContext(AiRunPersistence(store, "conversation", "third")) {
      runtime.runWith(second, context, emptyList(), "追问", {}, {}, tools())
    }
    assertEquals(1, second.prompts.last().messages.count { it.textContent() == "概览" })
    assertEquals(1, second.prompts.last().messages.count { it.textContent() == "追问" })
    assertTrue(second.prompts.last().messages.any { it.textContent().contains("早先读到的内容可能已被论坛清理") })
  }

  @Test fun failedCheckpointWriteStopsBeforeNextPaidRequestAndRetainsDraft() = runTest {
    val store = MemoryAiConversationStore().apply { failKind = "checkpoint" }
    val seen = mutableListOf<StreamFrame>()
    val executor = Executor { flowOf(StreamFrame.TextDelta("已有草稿"), StreamFrame.TextComplete("已有草稿"), StreamFrame.End("stop")) }
    val binding = AiRunPersistence(store, "conversation", "run")
    assertTrue(runCatching { withContext(binding) { runtime.runWith(executor, context, emptyList(), "概览", { seen += it }, {}) } }.isFailure)
    assertTrue(binding.failed)
    assertEquals(1, executor.prompts.size)
    assertTrue(seen.any { it is StreamFrame.TextDelta && it.text == "已有草稿" })
    assertTrue(runCatching { binding.beforeRequest() }.isFailure)
    assertEquals(1, store.requests)
  }
  @Test fun consecutiveInterruptionsStillLocateLastDurableCheckpointAndToolResult() = runTest {
    val store = MemoryAiConversationStore()
    store.runs["second"] = com.chasel.ng2n.data.db.AiRunEntity("second", "conversation", "interrupted", "启动", 2, "first")
    store.work("conversation", "checkpoint", "first", "checkpoint")
    store.work("conversation", "tool:first", "read", "result")
    val binding = AiRunPersistence(store, "conversation", "third", "second")
    assertEquals("checkpoint", binding.priorWork("checkpoint"))
    assertEquals("result", binding.priorWork("tool", "read"))
    assertNull(AiRunPersistence(store, "other-conversation", "third", "second").priorWork("checkpoint"))
  }

  @Test fun interruptedImageRequestRestoresMediaInSameToolSession() = imageRecovery(false)
  @Test fun interruptedImageRequestRestoresMediaInNewToolSession() = imageRecovery(true)

  private fun imageRecovery(newSession: Boolean) = runTest {
    val store = MemoryAiConversationStore()
    val page = com.chasel.ng2n.core.api.TopicDetail(tid = 10, subject = "图片主题", attachBase = "",
      floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 20, lou = 1, authorKey = "a",
        content = "[img]https://example.org/later.jpg[/img]")))
    var imageReads = 0
    var pageReads = 0
    fun session(initial: TopicContext) = ForumToolSession(initial, { pageReads++; page }, { emptyList() },
      imageReader = { imageReads++; "data:image/jpeg;base64,YQ==" }, limiter = ForumReadLimiter(0))
    val imageContext = context.copy(sources = listOf(com.chasel.ng2n.core.ai.AiSource("s1", 10, 20, 1, 1, "作者", "今天", "图示", listOf("https://example.org/later.jpg"))))
    val original = session(imageContext)
    val first = Executor { number -> flow {
      when (number) {
        1 -> emit(StreamFrame.ToolCallComplete("image", "read_image", """{"imageId":"s1:image1"}"""))
        else -> { emit(StreamFrame.TextDelta("读图草稿")); error("图片请求断线") }
      }
      emit(StreamFrame.End("tool_calls"))
    } }
    assertTrue(runCatching { withContext(AiRunPersistence(store, "images", "A")) {
      runtime.runWith(first, imageContext, emptyList(), "请看图片", {}, {}, original)
    } }.isFailure)
    val resumedContext = Json.decodeFromString<TopicContext>(store.work("images", "context", "current")!!)
    val resumed = if (newSession) session(resumedContext) else original
    val readsBeforeResume = pageReads
    val second = Executor { number -> flow {
      if (number == 1) assertEquals(readsBeforeResume, pageReads)
      if (number == 1) emit(StreamFrame.ToolCallComplete("more", "list_images", """{"tid":10,"pid":20}"""))
      else emit(StreamFrame.TextComplete("图片分析完成"))
      emit(StreamFrame.End(if (number == 1) "tool_calls" else "stop"))
    } }
    withContext(AiRunPersistence(store, "images", "B", "A")) {
      runtime.runWith(second, resumedContext, emptyList(), "请看图片", {}, {}, resumed)
    }
    assertEquals(1, imageReads)
    assertEquals(2, second.prompts.size)
    for (prompt in second.prompts) {
      val media = prompt.messages.filterIsInstance<Message.User>().filter { it.textContent().startsWith("工具读取的图片资料 s1:image1，") }
      assertEquals(1, media.size)
      assertEquals(2, media.single().parts.size)
      assertTrue(Json.encodeToString(Message.serializer(), media.single()).contains("YQ=="))
      assertEquals(1, prompt.messages.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().count { it.id == "image" })
    }
  }

}
