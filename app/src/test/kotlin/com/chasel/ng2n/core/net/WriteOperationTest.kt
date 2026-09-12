package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.SwitchAccountStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteOperationTest {

  private fun writeRequest(path: String = "nuke.php", query: QueryParams = emptyMap()) =
    NgaRequest(path = path, operation = Operation.WRITE, query = query)

  private fun fullChainClient(
    transport: Transport,
    accounts: List<Credential> = listOf(ALICE, BOB),
  ) = testClient(
    transport,
    strategies = listOf(
      FormatRotationStrategy(
        listOf(ResponseFormat.JSON, ResponseFormat.JSON_LITE),
        listOf("https://bbs.nga.cn", "https://ngabbs.com"),
      ),
      SwitchAccountStrategy { accounts },
    ),
    credentials = FakeCredentials(signedIn = accounts.first(), accounts = accounts),
  )

  @Test
  fun `写操作只发一次,失败也不换格式不换域名`() = runTest {
    val transport = RecordingTransport { blocked() }
    val client = NgaClient(
      transports = object : TransportFactory {
        override fun create(): Transport = transport
        override fun renew(): Transport = transport
      },
      credentials = FakeCredentials(signedIn = ALICE, accounts = listOf(ALICE, BOB)),
      settings = FakeSettings(),
      userAgents = UserAgents.fallback(),
      readChain = NgaClient.defaultReadChain(listCredentials = { listOf(ALICE, BOB) }),
    )

    assertThrowsNga { client.execute(writeRequest(query = queryOf("__lib" to "check_in"))) }

    assertEquals(1, transport.requests.size, "写操作必须只发一次")
    assertEquals(listOf(ALICE.uid), transport.uids(), "写操作不许换账号")
  }

  @Test
  fun `读操作同样条件下会轮换——对照组,证明上一条不是链本身没跑`() = runTest {
    val transport = RecordingTransport { blocked() }
    val client = NgaClient(
      transports = object : TransportFactory {
        override fun create(): Transport = transport
        override fun renew(): Transport = transport
      },
      credentials = FakeCredentials(signedIn = ALICE, accounts = listOf(ALICE, BOB)),
      settings = FakeSettings(),
      userAgents = UserAgents.fallback(),
      readChain = NgaClient.defaultReadChain(listCredentials = { listOf(ALICE, BOB) }),
    )

    assertThrowsNga { client.execute(readRequest("nuke.php", queryOf("__lib" to "check_in"))) }

    assertTrue(transport.requests.size > 1, "读操作该把组合空间跑一遍")
    assertTrue(transport.uids().contains(BOB.uid), "读操作该轮到换账号那一档")
  }

  @Test
  fun `format-rotation 自己也拦写操作(装配错了也不会重放)`() = runTest {
    val transport = RecordingTransport { blocked() }
    val misassembled = NgaClient(
      transports = object : TransportFactory {
        override fun create(): Transport = transport
        override fun renew(): Transport = transport
      },
      credentials = FakeCredentials(signedIn = ALICE, accounts = listOf(ALICE, BOB)),
      settings = FakeSettings(),
      userAgents = UserAgents.fallback(),
      readChain = NgaClient.defaultReadChain(listCredentials = { listOf(ALICE, BOB) }),
      writeChain = NgaClient.defaultReadChain(listCredentials = { listOf(ALICE, BOB) }),
    )

    val error = assertThrowsNga { misassembled.execute(writeRequest()) }

    assertEquals(0, transport.requests.size, "两道闸都挡住时一次请求都不该发出去")
    assertEquals(NgaErrorKind.UNAVAILABLE, error.kind)
  }

  @Test
  fun `switch-account 自己也拦写操作·换账号重发会把写入落到别人头上`() = runTest {
    val transport = RecordingTransport { blocked() }
    val strategy = SwitchAccountStrategy { listOf(ALICE, BOB) }
    val outcome = strategy.run(
      writeRequest(),
      FetchContext(
        transport = transport,
        host = DEFAULT_NGA_HOST,
        authMode = AuthMode.BOTH,
        credential = ALICE,
        userAgents = UserAgents.fallback(),
      ),
    )

    assertTrue(outcome is StrategyOutcome.Failed)
    assertEquals(NgaErrorKind.UNAVAILABLE, outcome.error.kind)
    assertEquals(0, transport.requests.size)
  }

  @Test
  fun `accountPolicy 为 PINNED 的读请求也不换账号`() = runTest {
    val transport = RecordingTransport { blocked() }
    val pinned = NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      accountPolicy = AccountPolicy.PINNED,
    )
    val outcome = SwitchAccountStrategy { listOf(ALICE, BOB) }.run(
      pinned,
      FetchContext(
        transport = transport,
        host = DEFAULT_NGA_HOST,
        authMode = AuthMode.BOTH,
        credential = ALICE,
        userAgents = UserAgents.fallback(),
      ),
    )

    assertTrue(outcome is StrategyOutcome.Failed)
    assertEquals(NgaErrorKind.UNAVAILABLE, outcome.error.kind)
  }

  @Test
  fun `写操作的默认账号策略是 PINNED,读是 FALLBACK`() {
    assertEquals(AccountPolicy.PINNED, writeRequest().accountPolicy)
    assertEquals(AccountPolicy.FALLBACK, readRequest("thread.php").accountPolicy)
  }

  @Test
  fun `写操作走 direct 一档也照样带凭证与表单字段`() = runTest {
    val transport = RecordingTransport { ok() }
    val client = NgaClient(
      transports = object : TransportFactory {
        override fun create(): Transport = transport
        override fun renew(): Transport = transport
      },
      credentials = FakeCredentials(signedIn = ALICE, accounts = listOf(ALICE, BOB)),
      settings = FakeSettings(),
      userAgents = UserAgents.fallback(),
      readChain = listOf(DirectStrategy()),
    )

    client.execute(writeRequest(query = queryOf("__lib" to "topic_recommend")))

    assertEquals(ALICE, transport.requests[0].credential)
    assertTrue(
      String(transport.requests[0].body!!, Charsets.ISO_8859_1)
        .contains("access_uid=${ALICE.uid}"),
    )
  }

  @Test
  fun `NgaRequest 的 operation 没有默认值`() {
    val ctor = NgaRequest::class.constructors.single()
    val operation = ctor.parameters.single { it.name == "operation" }
    assertTrue(!operation.isOptional, "operation 一旦有默认值,漏标就会静默走错链")
  }

  @Test
  fun `cacheScope 把 uid 拧进数据层缓存 key,游客与账号互不复用`() {
    val request = readRequest("nuke.php", queryOf("__lib" to "ucp", "fid" to 650, "page" to 1))

    assertEquals("10000001|nuke.php?fid=650&page=1", request.cacheScope("10000001"))
    assertEquals("guest|nuke.php?fid=650&page=1", request.cacheScope(null))
    assertTrue(request.cacheScope("1") != request.cacheScope("2"))
  }

  @Test
  fun `组合缓存 key 反过来不含 uid——它记的是服务端封了哪个组合,与谁在用无关`() {
    val mine = readRequest("thread.php", queryOf("fid" to 650))
    val yours = readRequest("thread.php", queryOf("fid" to 428))
    assertEquals(interfaceKeyOf(mine), interfaceKeyOf(yours))
    assertEquals("thread.php", interfaceKeyOf(mine))
  }
}
