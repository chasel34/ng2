package com.chasel.ng2n.data.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class AiRetryTest {
  private val success = """data: {"id":"test","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"回答"},"finish_reason":null}]}

data: {"id":"test","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"test","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_cache_hit_tokens":60}}

data: [DONE]

"""
  @Test fun onlyExplicitHttpErrorsBeforeOutputAreRetried() = runTest {
    for (status in listOf(401, 402, 400, 422, 429, 500, 503)) {
      MockWebServer().use { server ->
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        server.enqueue(MockResponse.Builder().code(status).body("error").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(success).build())
        val client = RetryingLLMClient(DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(
          baseUrl = server.url("/").toString())), DeepSeekClientFactory.retryConfig)
        try {
          val result = runCatching { client.executeStreaming(prompt("retry") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList()).toList() }
          assertEquals("HTTP $status", status in listOf(429, 500, 503), result.isSuccess)
          assertEquals(if (status in listOf(429, 500, 503)) 2 else 1, server.requestCount)
        } finally { client.close() }
      }
    }
  }
  @Test fun malformedStreamAfterTextIsNeverReplayed() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(success.substringBefore("data: [DONE]") + "data: invalid 429\n\n").build())
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(success).build())
      val client = RetryingLLMClient(DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString())), DeepSeekClientFactory.retryConfig)
      try {
        runCatching { client.executeStreaming(prompt("no-replay") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList()).toList() }
        assertEquals(1, server.requestCount)
      } finally { client.close() }
    }
  }
  @Test fun emptyStreamIsNeverReplayed() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body("data: [DONE]\n\n").build())
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(success).build())
      val client = RetryingLLMClient(KnownResultClient(DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))), DeepSeekClientFactory.retryConfig)
      try {
        runCatching { client.executeStreaming(prompt("empty") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList()).toList() }
        assertEquals(1, server.requestCount)
      } finally { client.close() }
    }
  }
  @Test fun longRetryAfterPausesWithoutSendingAgain() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").body("error").build())
      val client = RetryingLLMClient(KnownResultClient(DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))), DeepSeekClientFactory.retryConfig)
      try {
        assertTrue(runCatching { client.executeStreaming(prompt("waiting") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList()).toList() }.isFailure)
        assertEquals(1, server.requestCount)
      } finally { client.close() }
    }
  }
  @Test fun actualRetryHasItsOwnReservationAndCacheUsageSettlesThroughEvents() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().code(429).body("error").build())
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(success).build())
      val store = MemoryAiBudgetRepository()
      val budget = AiRunBudget(store, store.begin("conversation"))
      val client = RetryingLLMClient(KnownResultClient(DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()), budget)), DeepSeekClientFactory.retryConfig)
      val executor = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)
      try {
        kotlinx.coroutines.withContext(budget) { TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor,
          com.chasel.ng2n.core.ai.TopicContext(emptyList(), null, 0, listOf(1)), emptyList(), "概览", {}, {}) }
        val records = store.books.value.requests
        assertEquals(2, server.requestCount)
        assertEquals(2, records.size)
        assertEquals("pending_verification", records.first().status)
        assertTrue(records.last().retry)
        assertEquals("settled", records.last().status)
        assertEquals(60L, records.last().cached)
        assertEquals(com.chasel.ng2n.core.ai.AiPrice().cost(100, 20, 60), records.last().cost)
      } finally { executor.close() }
    }
  }
  @Test fun deterministicClientErrorsReleaseTheReservation() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().code(415).body("unsupported media type").build())
      val store = MemoryAiBudgetRepository()
      val budget = AiRunBudget(store, store.begin("conversation"))
      val client = RetryingLLMClient(KnownResultClient(DeepSeekClientFactory().create("offline-key",
        DeepSeekClientSettings(baseUrl = server.url("/").toString()), budget)), DeepSeekClientFactory.retryConfig)
      val executor = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)
      val failure = try {
        runCatching { kotlinx.coroutines.withContext(budget) { TopicAgentRuntime(DeepSeekClientFactory()).runWith(executor,
          com.chasel.ng2n.core.ai.TopicContext(emptyList(), null, 0, listOf(1)), emptyList(), "概览", {}, {}) } }
      } finally { executor.close() }
      assertEquals(1, server.requestCount)
      val record = store.books.value.requests.single()
      assertEquals("rejected", record.status)
      assertEquals(0L, record.charged)
      assertEquals(com.chasel.ng2n.core.ai.AiFailure.PARAMETERS,
        com.chasel.ng2n.core.ai.classifyAiFailure(requireNotNull(failure.exceptionOrNull()), false))
    }
  }
  @Test fun unknownNetworkFailuresNeverMatchRetryPolicy() {
    for (message in listOf("timeout", "connection reset", "HTTP 429 in body", "500", "AI_RETRY_HTTP 401")) {
      assertFalse(DeepSeekClientFactory.retryConfig.retryablePatterns.any { it.matches(message) })
    }
  }
}
