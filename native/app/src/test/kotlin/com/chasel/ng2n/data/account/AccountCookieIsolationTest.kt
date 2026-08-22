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

/**
 * 票 15 验收③的 CookieJar 那一半:**登出 / 切号之后,真发出去的请求带的是谁**。
 *
 * 起一个 `mockwebserver3` 真服务端,走的是生产那条路:
 * `AccountStore.current()`(每请求现读)→ `HttpRequest.credential` →
 * 自管 `NgaCookieJar` → okhttp 的 `BridgeInterceptor` 装 `Cookie` 头。
 * 断言的是**服务端收到的头**,不是我们自己拼的字符串。
 *
 * 票 06 的 `OkHttpTransportTest` 已经证明了 jar 那一层的行为(下发的 Set-Cookie 不保存、
 * 一发一身份);这里补的是「账号状态变化之后,下一发请求的身份跟着变」这条端到端链路。
 */
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

  /** 一发请求,凭证按生产路径**现读**当前账号。 */
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

    // 登进来的立刻是当前账号
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

    store.remove("10000002") // 退的是当前账号
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
    // WebView 里还留着上一个账号(RN 版 P1-03 的典型现场)
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
