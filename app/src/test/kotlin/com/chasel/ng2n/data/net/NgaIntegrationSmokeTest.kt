package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.InMemoryComboCache
import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.UserAgents
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.queryOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

class NgaIntegrationSmokeTest {

  private fun proxy(): Proxy {
    val spec = System.getenv("NGA_TEST_PROXY") ?: return Proxy.NO_PROXY
    val (host, port) = spec.substringAfter("://").split(":")
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port.toInt()))
  }

  private fun client(): NgaClient {
    val http: OkHttpClient = ngaHttpClientBuilder().proxy(proxy()).build()
    return NgaClient(
      transports = OkHttpTransportFactory(http),
      credentials = object : CredentialSource {
        override suspend fun current(): Credential? = null
        override suspend fun all(): List<Credential> = emptyList()
      },
      settings = NetworkSettingsSource.defaults(),
      userAgents = UserAgents.fallback(),
      comboCache = InMemoryComboCache(),
      readChain = NgaClient.defaultReadChain(listCredentials = { emptyList() }),
    )
  }

  @Test
  fun `游客态拉一页 thread_php 能拿到主题列表的形状`() = runTest {
    assumeTrue("默认跳过;要跑请设 NGA_INTEGRATION=1", System.getenv("NGA_INTEGRATION") == "1")

    val result = client().execute(
      NgaRequest(
        path = "thread.php",
        operation = Operation.READ,
        query = queryOf("fid" to 7, "page" to 1),
        validate = { envelope ->
          val data = envelope.data as? JsonObject
          if (data != null && listOf("__T", "__F", "__ROWS").any { data.containsKey(it) }) {
            null
          } else {
            "响应里没有主题列表结构"
          }
        },
      ),
    )

    val data = result.data as JsonObject
    assertTrue(
      listOf("__T", "__F", "__ROWS").any { data.containsKey(it) },
      "拿回来的 data 顶层键是 ${data.keys}",
    )
    println("[integration] via=${result.via} keys=${data.keys}")
  }
}
