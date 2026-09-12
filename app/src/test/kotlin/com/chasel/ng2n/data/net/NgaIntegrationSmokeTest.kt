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

/**
 * 联网冒烟:真的打一次 NGA,看整条链在真实网络上还立不立得住。
 *
 * ## 默认跳过 —— 这是**整票唯一**允许的跳过
 *
 * 铁律是「禁止用 `@Ignore` / 跳过来变绿」。这一条例外,因为它的失败可能与代码无关
 * (没网、NGA 在限流、CI 机器出不去),让它进默认单测集会把「红」的含义稀释掉。
 * 用 `Assume` 而不是 `@Ignore`:门控是运行时的环境变量,打开就真跑。
 *
 * ```bash
 * cd native
 * # 走代理时(gradle 的 Test JVM 不认 shell 的 http_proxy,靠下面这个变量转达)
 * NGA_INTEGRATION=1 NGA_TEST_PROXY=127.0.0.1:7897 \
 *   ./gradlew :app:testDebugUnitTest --tests '*NgaIntegrationSmokeTest' --rerun-tasks
 * ```
 *
 * `--rerun-tasks` 是必须的:输入没变时 gradle 会判 UP-TO-DATE 直接跳过整个 task。
 *
 * ## 纪律
 *
 * - **游客态**,不碰账号(凭证要真人给,见票面「真人介入」);
 * - 只拉 `thread.php?fid=7` 一页,**只断言形状**(`__T` / `__F` / `__ROWS` 至少有一个),
 *   不断言条数与内容 —— 那些每分钟都在变;
 * - **不要连续打**:NGA 会因为背靠背冷启动限流(T6),两次之间静置 ≥60s。
 *   所以这里只有一条用例,不做参数化。
 */
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
      // 真机上这里是系统 WebView UA;JVM 里拿不到,用兜底常量
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
