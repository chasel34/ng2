package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.ALICE
import com.chasel.ng2n.core.net.BOB
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.FakeCredentials
import com.chasel.ng2n.core.net.FakeSettings
import com.chasel.ng2n.core.net.FakeResponse
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.RecordingTransport
import com.chasel.ng2n.core.net.Transport
import com.chasel.ng2n.core.net.TransportFactory
import com.chasel.ng2n.core.net.UserAgents
import com.chasel.ng2n.core.net.blocked
import com.chasel.ng2n.core.net.ok
import com.chasel.ng2n.core.net.testClient
import java.net.URI
import java.net.URLDecoder

/**
 * 端点层单测的共用夹具:一个录请求的假传输层 + 一个只有 direct 档的 client。
 *
 * TS 侧那批用例用真实抓包字节走整条链;Kotlin 这边**解析那一半已经由金样本盯死了**
 * (`api/…` 10 个 domain 113 条),所以这里用内联 JSON 应答,只钉
 * **请求装配**(路径 / query / form / Referer)与**错误分支**——
 * 那两样金样本装不下(README「不在金样本里的东西」)。
 */
class ApiFixture(private vararg val bodies: String) {

  val transport = RecordingTransport { nextResponse() }

  val client: NgaClient = testClient(transport)

  private fun nextResponse(): FakeResponse {
    val index = minOf(transport.requests.size, bodies.size) - 1
    return ok(bodies.getOrElse(index) { OK_TEXT })
  }

  val requests get() = transport.requests

  fun url(index: Int = 0): String = requests[index].url

  /** URL 上的 query,值已 percent 解码。 */
  fun query(index: Int = 0): Map<String, String> = decodePairs(URI(url(index)).rawQuery)

  /** 表单体,值已 percent 解码(GBK 那一档另有 `OutboundCharsetTest` 盯着)。 */
  fun form(index: Int = 0): Map<String, String> =
    decodePairs(requests[index].body?.toString(Charsets.ISO_8859_1))

  fun referer(index: Int = 0): String? = requests[index].headers["Referer"]

  private fun decodePairs(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split("&").mapNotNull { pair ->
      val parts = pair.split("=", limit = 2)
      if (parts.size != 2) return@mapNotNull null
      parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }.toMap()
  }

  companion object {
    /** 写操作的常见响应。 */
    const val OK_TEXT = """{"data":{"0":"操作成功"},"time":1}"""
  }
}

/**
 * 标准装配的 client(读链五档、写链只有 direct 一档),用来证明
 * **写端点真的标了 `operation = WRITE`**:同样一份「被封」的响应,
 * 读请求会轮换十几次并换账号,写请求只发一次(修 P1-01,见 `core/net/WriteOperationTest`)。
 */
class BlockedChainFixture(accounts: List<Credential> = listOf(ALICE, BOB)) {

  val transport = RecordingTransport { blocked() }

  val client = NgaClient(
    transports = object : TransportFactory {
      override fun create(): Transport = transport
      override fun renew(): Transport = transport
    },
    credentials = FakeCredentials(signedIn = accounts.first(), accounts = accounts),
    settings = FakeSettings(),
    userAgents = UserAgents.fallback(),
    readChain = NgaClient.defaultReadChain(listCredentials = { accounts }),
  )

  val attempts get() = transport.requests.size

  val uids get() = transport.uids()
}
