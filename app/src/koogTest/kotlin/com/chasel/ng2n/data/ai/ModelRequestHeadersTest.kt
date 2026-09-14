package com.chasel.ng2n.data.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ModelRequestHeadersTest {
  private val stream = """data: {"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"回答"},"finish_reason":null}]}

data: {"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"t","object":"chat.completion.chunk","created":1,"system_fingerprint":"offline","model":"deepseek-flash","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

data: [DONE]

"""

  @Test fun streamingRequestDeclaresJsonBody() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(stream).build())
      val client = DeepSeekClientFactory().create("offline-key", DeepSeekClientSettings(baseUrl = server.url("/").toString()))
      val streamed = try {
        runCatching { client.executeStreaming(prompt("headers") { user("测试") }, DeepSeekModels.DeepSeekV4Flash, emptyList()).toList() }
      } finally { client.close() }
      val request = server.takeRequest()
      assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';')?.trim())
      assertEquals("text/event-stream", request.headers["Accept"])
      assertTrue(Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject.containsKey("messages"))
      assertTrue(streamed.exceptionOrNull()?.message.orEmpty(), streamed.isSuccess)
    }
  }
}
