package com.chasel.ng2n.data.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import com.chasel.ng2n.core.ai.TopicContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ModelStreamingTest {
  // 每段都含多字节字符，配合按字节切分的分块让 UTF-8 序列横跨多个 chunk。
  private val pieces = (1..400).map { "第${it}段·中文✓ " }
  private val answer = pieces.joinToString("")

  private fun chunk(content: String) =
    """{"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{"content":${Json.encodeToString(kotlinx.serialization.serializer(), content)}},"finish_reason":null}]}"""

  private fun stream(): String = buildString {
    pieces.forEach { append("data: ${chunk(it)}\n\n") }
    append("""data: {"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""").append("\n\n")
    append("""data: {"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":400,"total_tokens":410}}""").append("\n\n")
    append("data: [DONE]\n\n")
  }

  private fun server(block: (MockWebServer) -> Unit) = MockWebServer().use { server ->
    server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
    block(server)
  }

  private fun MockWebServer.enqueueChunked(maxChunkSize: Int) = enqueue(MockResponse.Builder()
    .addHeader("Content-Type", "text/event-stream")
    .chunkedBody(Buffer().writeUtf8(stream()), maxChunkSize).build())

  @Test fun chunkedStreamReachesASlowConsumerWithoutLosingFramesOrTheEndFrame() = server { server ->
    // 任意字节边界：7 与 4096 都不会落在事件边界，也会把多字节字符切开。
    for (size in listOf(7, 4096)) {
      server.enqueueChunked(size)
      val client = DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))
      val frames = try {
        runBlocking {
          client.executeStreaming(prompt("streaming") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList())
            .onEach { Thread.sleep(1) }.toList()
        }
      } finally { client.close() }
      assertEquals("chunk=$size", answer, frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text })
      assertEquals("chunk=$size", answer, frames.toMessageResponse().textContent())
      assertTrue("chunk=$size 缺少结束帧：${frames.lastOrNull()}", frames.lastOrNull() is StreamFrame.End)
    }
  }

  @Test fun agentRuntimeStoresTheWholeAnswerWhenEveryFrameIsPersisted() = server { server ->
    server.enqueueChunked(13)
    val client = DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))
    val executor = MultiLLMPromptExecutor(client)
    val streamed = StringBuilder()
    val message = try {
      runBlocking {
        TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor, TopicContext(emptyList(), null, 0, listOf(1)),
          emptyList(), "概览", onFrame = { frame ->
            // 界面每收到一帧都会落库一次；消费端慢于服务端时不能丢帧。
            if (frame is StreamFrame.TextDelta) { streamed.append(frame.text); Thread.sleep(1) }
          }, onStart = {})
      }
    } finally { executor.close() }
    assertEquals(answer, message.textContent())
    assertEquals(answer, streamed.toString())
  }

  @Test fun cancellingMidStreamRaisesNoUncaughtFailure() = server { server ->
    server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
      .chunkedBody(Buffer().writeUtf8(stream()), 64).throttleBody(64, 20, TimeUnit.MILLISECONDS).build())
    val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    val client = DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))
    try {
      runBlocking {
        val received = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
          client.executeStreaming(prompt("streaming") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList())
            .collect { received.complete(Unit) }
        }
        withTimeout(10_000) { received.await() }
        job.cancelAndJoin()
      }
      Thread.sleep(500)
    } finally {
      client.close()
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
    assertEquals(uncaught.joinToString { it.toString() }, emptyList<Throwable>(), uncaught.toList())
  }
}
