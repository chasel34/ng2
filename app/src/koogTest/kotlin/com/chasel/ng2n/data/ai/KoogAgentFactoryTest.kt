package com.chasel.ng2n.data.ai

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class KoogAgentFactoryTest {
  @Test
  fun modelToolResultAndFinalAnswerUseTheFrameworkLoop() = runTest {
    val steps = mutableListOf<String>()
    val tool = object : SimpleTool<PageArgs>(typeToken<PageArgs>(), "read_page", "Read a topic page") {
      override suspend fun execute(args: PageArgs): String {
        assertEquals(2, args.page)
        steps += "tool"
        return "page 2 evidence"
      }
    }
    val executor = object : PromptExecutor() {
      override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        assertEquals(listOf("read_page"), tools.map { it.name })
        val results = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        return if (results.isEmpty()) {
          steps += "request"
          assertTrue(prompt.messages.any { it.textContent() == "Read page 2" })
          Message.Assistant(
            MessagePart.Tool.Call("call-1", "read_page", """{"page":2}"""),
            ResponseMetaInfo(Clock.System.now()),
          )
        } else {
          steps += "result"
          assertEquals(1, results.size)
          assertEquals("call-1", results.single().id)
          assertEquals("read_page", results.single().tool)
          assertEquals("page 2 evidence", results.single().output)
          Message.Assistant("Final answer from page 2", ResponseMetaInfo(Clock.System.now()))
        }
      }
      override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("Unexpected streaming request")
      override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Unexpected moderation")
      override fun close() { steps += "closed" }
    }
    try {
      val answer = KoogAgentFactory().run(
        "Read page 2",
        executor,
        AIAgentConfig(prompt("offline") { system("Use the supplied reading tool") }, DeepSeekModels.DeepSeekV4Flash, 10),
        ToolRegistry { tool(tool) },
      )
      assertEquals("Final answer from page 2", answer)
      assertEquals(listOf("request", "tool", "result"), steps)
    } finally {
      executor.close()
    }
  }

  @Serializable
  data class PageArgs(val page: Int)
}
