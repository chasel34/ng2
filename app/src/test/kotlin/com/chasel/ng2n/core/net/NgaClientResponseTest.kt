package com.chasel.ng2n.core.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NgaClientResponseTest {

  @Test
  fun `真实抓包·通知接口解出 data`() = runTest {
    val transport = RecordingTransport { NetFixture.NOTI_EMPTY.response() }
    val result = testClient(transport)
      .execute(readRequest("nuke.php", queryOf("__lib" to "noti", "__act" to "get_all")))

    assertEquals("direct", result.via)
    assertEquals("", (result.data as JsonObject).getValue("0").jsonPrimitive.content)
  }

  @Test
  fun `真实抓包·未声明 charset 的 GBK 主题列表照样解出中文`() = runTest {
    val transport = RecordingTransport { NetFixture.THREAD_LIST.response() }
    val result = testClient(transport).execute(readRequest("thread.php", queryOf("fid" to 650)))

    val name = (result.data as JsonObject).getValue("__F").jsonObject.getValue("name")
    assertEquals("原神", name.jsonPrimitive.content)
  }

  @Test
  fun `HTTP 非 2xx 时先解析 body,body 有错误信息就报服务端错误`() = runTest {
    val transport = RecordingTransport { NetFixture.READ_THREAD_NOT_FOUND.response(status = 403) }
    val client = testClient(transport)

    val error = assertThrowsNga { client.execute(readRequest("read.php", queryOf("tid" to 1))) }
    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertTrue(error.text.contains("找不到主题"), error.text)
  }

  @Test
  fun `HTTP 非 2xx 且 body 有正常数据时,仍然当成功`() = runTest {
    val transport = RecordingTransport { NetFixture.UCP_USER.response(status = 500) }
    val result = testClient(transport).execute(readRequest("nuke.php"))

    val username = (result.data as JsonObject).getValue("0").jsonObject.getValue("username")
    assertEquals("BugenZhao", username.jsonPrimitive.content)
  }

  @Test
  fun `HTTP 非 2xx 且 body 为空才用状态码报错`() = runTest {
    val transport = RecordingTransport {
      FakeResponse(status = 502, contentType = "text/html", body = utf8(""))
    }
    val client = testClient(transport)

    val error = assertThrowsNga { client.execute(readRequest("nuke.php")) }
    assertEquals(NgaErrorKind.HTTP, error.kind)
    assertEquals(502, error.status)
    assertTrue(error.retryable)
  }

  @Test
  fun `非 2xx 但 body 有内容只是解析不了则报 parse(被封的信号),状态码一并带上`() = runTest {
    val transport = RecordingTransport {
      FakeResponse(status = 403, contentType = "text/html", body = utf8("<html>Forbidden</html>"))
    }
    val client = testClient(transport)

    val error = assertThrowsNga { client.execute(readRequest("nuke.php")) }
    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(403, error.status)
    assertTrue(error.retryable)
  }

  @Test
  fun `假错误当成功,data 为空由调用方判断`() = runTest {
    val transport = RecordingTransport { NetFixture.UCP_NOT_FOUND.response() }
    val result = testClient(transport).execute(readRequest("nuke.php", queryOf("__lib" to "ucp")))

    assertEquals("找不到用户", result.fakeError?.message)
    assertNull(result.data)
  }

  @Test
  fun `传输层异常归为 network 错误`() = runTest {
    val transport = Transport { throw java.io.IOException("连接超时") }
    val client = testClient(transport)

    val error = assertThrowsNga { client.execute(readRequest("nuke.php")) }
    assertEquals(NgaErrorKind.NETWORK, error.kind)
    assertTrue(error.retryable)
  }

  @Test
  fun `调用方取消不算被封,不触发后面的兜底`() = runTest {
    val fallback = StubStrategy("cache")
    val transport = Transport { throw kotlinx.coroutines.CancellationException("Aborted") }
    val client = testClient(
      transport,
      strategies = listOf(com.chasel.ng2n.core.net.strategies.DirectStrategy(), fallback),
    )

    var cancelled = false
    try {
      client.execute(readRequest("nuke.php"))
    } catch (_: kotlinx.coroutines.CancellationException) {
      cancelled = true
    }

    assertTrue(cancelled, "取消应当原样抛出")
    assertEquals(0, fallback.calls, "取消之后链上后面的兜底一档都不该跑")
  }

  @Test
  fun `XML 与 HTML 格式暂不由 direct 解析,给出明确错误`() = runTest {
    val transport = RecordingTransport { ok() }
    val client = testClient(transport)

    val error = assertThrowsNga {
      client.execute(readRequest("read.php", format = ResponseFormat.XML))
    }
    assertEquals(NgaErrorKind.UNAVAILABLE, error.kind)
  }
}
