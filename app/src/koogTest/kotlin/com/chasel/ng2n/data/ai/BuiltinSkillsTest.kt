package com.chasel.ng2n.data.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.chasel.ng2n.core.ai.TopicContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuiltinSkillsTest {
  private val assets = File("src/main/assets")
  private fun release(base: File, version: String) = releaseSkills(base, version,
    { File(assets, it).list()?.toList().orEmpty() }, { File(assets, it).readBytes() })

  @Test fun upgradeReleasesReferencesAndRemovesOldVersionOnlyAfterSuccess() {
    val base = Files.createTempDirectory("skills").toFile()
    try {
      val first = release(base, "1")
      assertTrue(File(first, "fact-check/references/example.md").isFile)
      try { releaseSkills(base, "2", { emptyList() }, { error("asset failure") }); fail() } catch (_: IllegalStateException) { }
      assertTrue(first.isDirectory)
      val second = release(base, "2")
      assertFalse(first.exists())
      assertEquals(listOf("2"), base.list()!!.toList())
      assertEquals(second, release(base, "2"))
    } finally { base.deleteRecursively() }
  }

  @Test fun conversationRetainsEachRunsVersionAfterUpgrade() = runTest {
    val base = Files.createTempDirectory("skills-versions").toFile()
    val store = MemoryAiConversationStore()
    try {
      for (version in listOf("1", "2")) {
        val catalog = SkillCatalog.create(release(base, version))
        val executor = object : PromptExecutor() {
          override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("stream only")
          override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
            val directories = prompt.messages.filterIsInstance<Message.System>().filter { it.textContent().contains("<available_skills>") }
            assertEquals(1, directories.size)
            assertTrue(directories.single().textContent().contains(catalog.root.absolutePath))
            emit(StreamFrame.TextComplete("回答")); emit(StreamFrame.End("stop"))
          }
          override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
          override fun close() = Unit
        }
        kotlinx.coroutines.withContext(AiRunPersistence(store, "conversation", "run-$version")) {
          TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor, TopicContext(emptyList(), null, 0, listOf(1)), emptyList(), "问题", {}, {}, catalog = catalog)
        }
      }
      assertEquals("1", store.work("conversation", "skills", "run-1"))
      assertEquals("2", store.work("conversation", "skills", "run-2"))
    } finally { base.deleteRecursively() }
  }

  @Test fun serializedFirstHttpRequestOmitsSkillBodiesAndExamples() = runTest {
    val base = Files.createTempDirectory("skills-http").toFile()
    try {
      val catalog = SkillCatalog.create(release(base, "1"))
      mockwebserver3.MockWebServer().use { server ->
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        val client = DeepSeekClientFactory().create("offline-key", ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings(
          baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()))
        val executor = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)
        val chunks = listOf(
          """{"id":"x","object":"chat.completion.chunk","system_fingerprint":"offline","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"外部核查未做"},"finish_reason":null}]}""",
          """{"id":"x","object":"chat.completion.chunk","system_fingerprint":"offline","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}""",
          "[DONE]")
        server.enqueue(mockwebserver3.MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
          .body(chunks.joinToString("") { "data: $it\n\n" }).build())
        try {
          TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor, TopicContext(emptyList(), null, 0, listOf(1)), emptyList(), "概览", {}, {}, catalog = catalog)
          val body = checkNotNull(server.takeRequest().body).utf8()
          assertTrue(body.contains("fact-check")); assertTrue(body.contains("__read_file__"))
          assertFalse(body.contains("拆分可核查的事实主张与个人感受"))
          assertFalse(body.contains("虚构回答示例"))
          assertFalse(body.contains("execute_python"))
        } finally { executor.close() }
      }
    } finally { base.deleteRecursively() }
  }

  @Test fun firstRequestContainsCatalogOnlyAndFrameworkReadLoadsBodyWithoutScriptTools() = runTest {
    val base = Files.createTempDirectory("skills").toFile()
    try {
      val root = release(base, "1")
      val catalog = SkillCatalog.create(root)
      val body = "拆分可核查的事实主张与个人感受"
      assertFalse(catalog.prompt.contains(body))
      assertEquals(setOf("__read_file__", "__list_directory__"), catalog.registry.tools.map { it.name }.toSet())
      var calls = 0
      val executor = object : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("stream only")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
          calls++
          val text = prompt.messages.joinToString { it.textContent() + it.parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().joinToString { part -> part.output } }
          assertTrue(text.contains("fact-check"))
          assertTrue(text.contains("提取说法并用站外资料核对证据"))
          assertFalse(text.contains("虚构回答示例"))
          if (calls == 1) {
            assertFalse(text.contains(body))
            val path = File(root, "fact-check/SKILL.md").absolutePath
            emit(StreamFrame.ToolCallComplete("skill", "__read_file__", """{"path":"$path"}"""))
            emit(StreamFrame.End("tool_calls"))
          } else {
            assertTrue(text.contains(body))
            emit(StreamFrame.TextComplete("外部核查未做")); emit(StreamFrame.End("stop"))
          }
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() = Unit
      }
      TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor, TopicContext(emptyList(), null, 0, listOf(1)), emptyList(), "概览", {}, {}, catalog = catalog)
      assertEquals(2, calls)
      @Suppress("UNCHECKED_CAST")
      val read = catalog.registry.tools.single { it.name == "__read_file__" } as ReadFileTool<File>
      assertTrue(read.execute(ReadFileTool.Args(File(root, "fact-check/SKILL.md").absolutePath)).toString().contains(body))
      val outside = File(base, "secret.md").apply { writeText("private") }
      for (path in listOf(outside, File(root, "../secret.md"))) {
        try { read.execute(ReadFileTool.Args(path.absolutePath)); fail("outside directory") } catch (_: ai.koog.agents.core.tools.ToolException) { }
      }
      Files.createSymbolicLink(File(root, "secret.md").toPath(), outside.toPath())
      try { read.execute(ReadFileTool.Args(File(root, "secret.md").absolutePath)); fail("symlink") } catch (_: ai.koog.agents.core.tools.ToolException) { }
    } finally { base.deleteRecursively() }
  }

  @Test fun frameworkToolNameWithoutTrailingUnderscoresStillListsTheSkillSubdirectory() = runTest {
    val base = Files.createTempDirectory("skills-list").toFile()
    try {
      val root = release(base, "5-3")
      val catalog = SkillCatalog.create(root)
      var calls = 0
      var output = ""
      val executor = object : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("stream only")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
          if (calls++ == 0) emit(StreamFrame.ToolCallComplete("skill", "__list_directory",
            """{"absolutePath":"${File(root, "fact-check").absolutePath}","depth":2}"""))
          else {
            output = prompt.messages.flatMap { it.parts }.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Result>().joinToString { part -> part.output }
            emit(StreamFrame.TextComplete("好"))
          }
          emit(StreamFrame.End(if (calls == 1) "tool_calls" else "stop"))
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() = Unit
      }
      val rows = mutableListOf<ToolCallRow>()
      TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor, TopicContext(emptyList(), null, 0, listOf(1)),
        emptyList(), "概览", {}, {}, catalog = catalog, onUpdate = { if (it is AiStreamUpdate.Tool) rows += it.row })
      assertEquals(2, calls)
      assertTrue(output, output.contains("example.md"))
      assertEquals("__list_directory__", rows.first().name)
      assertEquals("事实核查", rows.first().arguments)
      assertEquals("ok", rows.last().status)
    } finally { base.deleteRecursively() }
  }
}
