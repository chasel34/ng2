package com.chasel.ng2n.data.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.data.net.NgaCookieJar
import com.chasel.ng2n.data.net.ngaHttpClientBuilder
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DeepSeekClientFactoryTest {
  @Test
  fun deepSeekRequestsDoNotUseForumCookiesOrInterceptors() = runTest {
    MockWebServer().use { server ->
      server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
      var forumCalls = 0
      val forumClient = ngaHttpClientBuilder()
        .cookieJar(NgaCookieJar { Credential("123", "forum-secret") })
        .addInterceptor { chain ->
          forumCalls++
          chain.proceed(chain.request().newBuilder().header("X-Forum-Client", "true").build())
        }
        .build()
      server.enqueue(MockResponse.Builder().body("ok").build())
      forumClient.newCall(Request.Builder().url(server.url("/read.php").newBuilder().host("127.0.0.1").build()).build()).execute().close()
      val forumRequest = server.takeRequest()
      assertTrue(forumRequest.headers["Cookie"]!!.contains("forum-secret"))
      val client = DeepSeekClientFactory().create("fake-model-key", DeepSeekClientSettings(baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()))
      try {
        repeat(2) {
          server.enqueue(
            MockResponse.Builder()
              .addHeader("Content-Type", "application/json")
              .addHeader("Set-Cookie", "ngaPassportCid=must-not-be-replayed; Path=/")
              .body("""{"id":"completion-1","object":"chat.completion","created":1,"system_fingerprint":"offline","model":"deepseek-v4-flash","choices":[{"index":0,"message":{"role":"assistant","content":"offline answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}""")
              .build(),
          )
          val result = client.execute(prompt("http-isolation") { user("Offline request") }, DeepSeekModels.DeepSeekV4Flash, emptyList())
          assertEquals("offline answer", result.textContent())
          val request = server.takeRequest()
          assertEquals("/chat/completions", request.url.encodedPath)
          assertNotEquals(forumRequest.connectionIndex, request.connectionIndex)
          assertEquals("Bearer fake-model-key", request.headers["Authorization"])
          assertNull(request.headers["Cookie"])
          assertNull(request.headers["X-Forum-Client"])
        }
        assertEquals(1, forumCalls)
      } finally {
        client.close()
        forumClient.connectionPool.evictAll()
        forumClient.dispatcher.executorService.shutdown()
      }
    }
  }

  @Test
  fun blankKeyIsRejectedBeforeCreatingAClient() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { DeepSeekClientFactory().create(" ") }
  }
}
