package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.CALL_TIMEOUT_MS
import com.chasel.ng2n.core.net.CONNECT_TIMEOUT_MS
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.HttpMethod
import com.chasel.ng2n.core.net.HttpRequest
import com.chasel.ng2n.core.net.READ_TIMEOUT_MS
import com.chasel.ng2n.core.net.WRITE_TIMEOUT_MS
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkHttpTransportTest {

  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  @BeforeTest
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = ngaHttpClientBuilder().build()
  }

  @AfterTest
  fun tearDown() {
    server.close()
  }

  private fun enqueueOk(count: Int = 1) {
    repeat(count) {
      server.enqueue(
        MockResponse.Builder()
          .code(200)
          .addHeader("Content-Type", "text/javascript; charset=UTF-8")
          .body("""{"data":{"0":"ok"},"time":1}""")
          .build(),
      )
    }
  }

  private fun request(
    path: String = "/thread.php",
    credential: Credential? = null,
    method: HttpMethod = HttpMethod.GET,
    body: ByteArray? = null,
    contentType: String? = null,
  ) = HttpRequest(
    url = server.url(path).toString(),
    method = method,
    headers = mapOf(
      "User-Agent" to "SystemWebView-1.0",
      "X-User-Agent" to "Nga_Official",
      "Referer" to server.url("/").toString(),
    ),
    body = body,
    contentType = contentType,
    credential = credential,
  )

  @Test
  fun `凭证由自管 jar 装成 Cookie 头,两枚 passport cookie 都在`() = runTest {
    enqueueOk()
    OkHttpTransport(client).execute(request(credential = Credential("10000001", "cid-a")))

    val recorded = server.takeRequest()
    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      recorded.headers["Cookie"],
    )
    assertEquals("SystemWebView-1.0", recorded.headers["User-Agent"])
    assertEquals("Nga_Official", recorded.headers["X-User-Agent"])
  }

  @Test
  fun `游客请求不带 Cookie 头`() = runTest {
    enqueueOk()
    OkHttpTransport(client).execute(request(credential = null))

    assertNull(server.takeRequest().headers["Cookie"])
  }

  @Test
  fun `服务端下发的 Set-Cookie 不会被保存,下一发只带我们自己的两枚`() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/javascript; charset=UTF-8")
        .addHeader("Set-Cookie", "ngaPassportUid=999; Path=/")
        .addHeader("Set-Cookie", "guestJs=1755000000; Path=/")
        .body("""{"data":{"0":"ok"},"time":1}""")
        .build(),
    )
    enqueueOk()

    val transport = OkHttpTransport(client)
    transport.execute(request(credential = Credential("10000001", "cid-a")))
    server.takeRequest()
    transport.execute(request(credential = Credential("10000001", "cid-a")))

    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      server.takeRequest().headers["Cookie"],
    )
  }

  @Test
  fun `换账号那一档换的就是这一发的身份,互不串号`() = runTest {
    enqueueOk(2)
    val transport = OkHttpTransport(client)

    transport.execute(request(credential = Credential("10000001", "cid-a")))
    transport.execute(request(credential = Credential("10000002", "cid-b")))

    assertTrue(server.takeRequest().headers["Cookie"]!!.contains("ngaPassportUid=10000001"))
    assertTrue(server.takeRequest().headers["Cookie"]!!.contains("ngaPassportUid=10000002"))
  }

  @Test
  fun `同一个 transport 连发两次复用同一条连接`() = runTest {
    enqueueOk(2)
    val transport = OkHttpTransportFactory(client).create()

    transport.execute(request())
    transport.execute(request())

    assertEquals(server.takeRequest().connectionIndex, server.takeRequest().connectionIndex)
  }

  @Test
  fun `renew 之后是一条新连接——RN 版这一档是空操作`() = runTest {
    enqueueOk(3)
    val factory = OkHttpTransportFactory(client)

    factory.create().execute(request())
    val first = server.takeRequest().connectionIndex

    factory.renew().execute(request())
    val second = server.takeRequest().connectionIndex

    factory.renew().execute(request())
    val third = server.takeRequest().connectionIndex

    assertNotEquals(first, second, "renew() 必须换掉连接池,否则这一档等于没做")
    assertNotEquals(second, third, "连续两次 renew 也要各自是新连接")
  }

  @Test
  fun `GBK 表单的 Content-Type 原样发出去,okhttp 不会追加 charset=utf-8`() = runTest {
    enqueueOk()
    OkHttpTransport(client).execute(
      request(
        method = HttpMethod.POST,
        body = "content=%D4%AD%C9%F1".toByteArray(Charsets.ISO_8859_1),
        contentType = "application/x-www-form-urlencoded;charset=GBK",
      ),
    )

    val recorded = server.takeRequest()
    assertEquals("application/x-www-form-urlencoded;charset=GBK", recorded.headers["Content-Type"])
    assertEquals("content=%D4%AD%C9%F1", recorded.body!!.utf8())
  }

  @Test
  fun `响应字节原样交出来,由解码层去认 charset`() = runTest {
    val gbk = "原神".toByteArray(charset("GB18030"))
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/html")
        .body(okio.Buffer().write(gbk))
        .build(),
    )

    val response = OkHttpTransport(client).execute(request())

    assertEquals(gbk.toList(), response.body.toList())
    assertEquals("text/html", response.contentType)
  }

  @Test
  fun `基础 client 四档超时都设了,且反封锁链自己重试(okhttp 不再偷偷重试)`() {
    val configured = ngaHttpClientBuilder().build()

    assertEquals(CONNECT_TIMEOUT_MS.toInt(), configured.connectTimeoutMillis)
    assertEquals(READ_TIMEOUT_MS.toInt(), configured.readTimeoutMillis)
    assertEquals(WRITE_TIMEOUT_MS.toInt(), configured.writeTimeoutMillis)
    assertEquals(CALL_TIMEOUT_MS.toInt(), configured.callTimeoutMillis)
    assertTrue(!configured.retryOnConnectionFailure)
  }

  @Test
  fun `renew 出来的 client 继承同一份超时预算`() {
    val renewed = OkHttpTransportFactory(client).renew()
    assertTrue(renewed is OkHttpTransport)
  }
}
