package com.chasel.ng2n.data.account

import com.chasel.ng2n.core.net.HttpMethod
import com.chasel.ng2n.core.net.HttpRequest
import com.chasel.ng2n.data.net.OkHttpTransport
import com.chasel.ng2n.data.net.ngaHttpClientBuilder
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountCookieIsolationTest {

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

  private suspend fun fetchAs(store: AccountStore) {
    OkHttpTransport(client).execute(
      HttpRequest(
        url = server.url("/thread.php").toString(),
        method = HttpMethod.GET,
        headers = mapOf("User-Agent" to "SystemWebView-1.0"),
        credential = store.current(),
      ),
    )
  }

  @Test
  fun `登出之后下一发请求不带任何 Cookie —— 游客态`() = runTest {
    enqueueOk(2)
    val store = inMemoryAccountStore()
    store.upsert(testAccount("10000001", cid = "cid-a"))

    fetchAs(store)
    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      server.takeRequest().headers["Cookie"],
    )

    store.remove("10000001")
    fetchAs(store)

    assertNull(
      server.takeRequest().headers["Cookie"],
      "退出之后 jar 里没有任何身份可装 —— 游客态不发认证信息",
    )
  }

  @Test
  fun `切号之后下一发请求换成新账号的 Cookie`() = runTest {
    enqueueOk(3)
    val store = inMemoryAccountStore()
    store.upsert(testAccount("10000001", cid = "cid-a"))
    store.upsert(testAccount("10000002", cid = "cid-b"))

    fetchAs(store)
    assertEquals(
      "ngaPassportUid=10000002; ngaPassportCid=cid-b",
      server.takeRequest().headers["Cookie"],
    )

    store.switchTo("10000001")
    fetchAs(store)
    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      server.takeRequest().headers["Cookie"],
      "切号在**下一发请求**生效(每请求现读)",
    )

    store.switchTo("10000002")
    fetchAs(store)
    assertEquals(
      "ngaPassportUid=10000002; ngaPassportCid=cid-b",
      server.takeRequest().headers["Cookie"],
    )
  }

  @Test
  fun `退出当前账号后落到剩余那个 请求带的是它`() = runTest {
    enqueueOk(1)
    val store = inMemoryAccountStore()
    store.upsert(testAccount("10000001", cid = "cid-a"))
    store.upsert(testAccount("10000002", cid = "cid-b"))

    store.remove("10000002")
    fetchAs(store)

    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      server.takeRequest().headers["Cookie"],
    )
  }

  @Test
  fun `WebView 那份 cookie 与请求身份互不相干`() = runTest {
    enqueueOk(1)
    val store = inMemoryAccountStore()
    val vault = FakeWebCookieVault()
    vault.cookie = "ngaPassportUid=99999999; ngaPassportCid=cid-stale"
    store.upsert(testAccount("10000001", cid = "cid-a"))

    fetchAs(store)

    assertEquals(
      "ngaPassportUid=10000001; ngaPassportCid=cid-a",
      server.takeRequest().headers["Cookie"],
      "请求身份只认 AccountStore;WebView 里是谁完全不影响它(修 P1-03)",
    )
  }
}
