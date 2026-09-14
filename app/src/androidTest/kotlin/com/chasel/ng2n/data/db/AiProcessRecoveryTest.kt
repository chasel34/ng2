package com.chasel.ng2n.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.chasel.ng2n.core.ai.TopicContext
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.data.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AiProcessRecoveryTest {
  private class Executor(val block: suspend (Int) -> Flow<StreamFrame>) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("stream only")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
      prompts += prompt
      emitAll(block(prompts.size))
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
    override fun close() = Unit
  }
  @Test fun processBoundary() = runBlocking<Unit> {
    val phase = InstrumentationRegistry.getArguments().getString("aiProcessPhase")
    assumeTrue(phase == "seed" || phase == "verify")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val name = "ai-process-recovery.db"
    if (phase == "seed") context.deleteDatabase(name)
    val db = Room.databaseBuilder(context, Ng2nDatabase::class.java, name).build()
    val store = RoomAiConversationStore(db)
    val initial = TopicContext(emptyList(), null, 0, listOf(1))
    val runtime = TopicAgentRuntime(DeepSeekClientFactory())
    val ready = File(context.filesDir, "ai-07-process-ready")
    try {
      store.initialize()
      if (phase == "seed") {
        ready.delete()
        store.save(AiConversationEntity("process", "进程恢复测试", "主题", 7, "主题 7", 1, 1, "生成中", "第 1 页", "{}", null),
          emptyList(), emptyList(), emptyList(), AiRunEntity("first", "process", "running", "工具已完成，正在生成回答", 1))
        val executor = Executor { request -> flow {
          if (request == 1) {
            emit(StreamFrame.ToolCallComplete("page", "read_topic_page", """{"tid":7}"""))
            emit(StreamFrame.End("tool_calls"))
          } else {
            emit(StreamFrame.TextDelta("进程结束前的部分回答"))
            db.aiConversationDao().messages(listOf(AiMessageEntity("process", 0, "进程结束前的部分回答")))
            ready.writeText("ready")
            awaitCancellation()
          }
        } }
        val tools = ForumToolSession(initial, { TopicDetail(tid = 7, subject = "离线资料", attachBase = "") }, { emptyList() }, limiter = ForumReadLimiter(0))
        withContext(AiRunPersistence(store, "process", "first")) {
          runtime.runWith(executor, initial, emptyList(), "测试问题", {}, {}, tools)
        }
      } else {
        val saved = store.load("process")!!
        assertEquals("已中断", saved.conversation.status)
        assertEquals("interrupted", saved.run!!.status)
        assertEquals("工具已完成，正在生成回答", saved.run.step)
        assertEquals("进程结束前的部分回答", saved.messages.single().payload)
        assertEquals(2, db.aiConversationDao().usageCount())
        val executor = Executor { flowOf(StreamFrame.TextComplete("恢复成功"), StreamFrame.End("stop")) }
        val tools = ForumToolSession(initial, { error("不得重复已成功读取") }, { emptyList() }, limiter = ForumReadLimiter(0))
        val result = withContext(AiRunPersistence(store, "process", "second", "first")) {
          runtime.runWith(executor, initial, emptyList(), "测试问题", {}, {}, tools)
        }
        assertEquals("恢复成功", result.textContent())
        assertEquals(1, executor.prompts.size)
        assertEquals(1, executor.prompts.single().messages.count { it.textContent() == "测试问题" })
        assertEquals(3, db.aiConversationDao().usageCount())
        ready.delete()
      }
    } finally { db.close() }
  }
}
