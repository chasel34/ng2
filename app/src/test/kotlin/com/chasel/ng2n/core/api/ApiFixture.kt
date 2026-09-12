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

class ApiFixture(private vararg val bodies: String) {

  val transport = RecordingTransport { nextResponse() }

  val client: NgaClient = testClient(transport)

  private fun nextResponse(): FakeResponse {
    val index = minOf(transport.requests.size, bodies.size) - 1
    return ok(bodies.getOrElse(index) { OK_TEXT })
  }

  val requests get() = transport.requests

  fun url(index: Int = 0): String = requests[index].url

  fun query(index: Int = 0): Map<String, String> = decodePairs(URI(url(index)).rawQuery)

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
    const val OK_TEXT = """{"data":{"0":"操作成功"},"time":1}"""
  }
}

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
