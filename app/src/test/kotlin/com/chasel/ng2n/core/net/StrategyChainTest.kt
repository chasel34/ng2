package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 逐条移植自 `src/core/net/fetcher.test.ts` 的
 * `runStrategyChain · 反封锁链框架(ADR-0002)`(7 条,全部移植)。
 */
class StrategyChainTest {

  private fun context(transport: Transport = RecordingTransport { ok() }): FetchContext =
    FetchContext(
      transport = transport,
      host = "https://bbs.nga.cn",
      authMode = AuthMode.NONE,
      credential = null,
      userAgents = UserAgents.fixed("w"),
    )

  private val request = readRequest("thread.php")

  @Test
  fun `第一个成功的策略产出结果,后面的不跑`() = runTest {
    val first = StubStrategy("first")
    val second = StubStrategy("second")

    val result = runStrategyChain(listOf(first, second), request, context())

    assertEquals("first", result.via)
    assertEquals(0, second.calls)
  }

  @Test
  fun `可重试的失败(解析失败约等于被封)会落到下一档`() = runTest {
    val blocked = StubStrategy("direct", NgaError(NgaErrorKind.PARSE, "解析失败"))
    val fallback = StubStrategy("cache")

    val result = runStrategyChain(listOf(blocked, fallback), request, context())

    assertEquals("cache", result.via)
    assertEquals(1, fallback.calls)
  }

  @Test
  fun `服务端语义错误不重试,立刻抛出,后面的兜底不浪费`() = runTest {
    val semantic = StubStrategy("direct", NgaError(NgaErrorKind.SERVER, "找不到主题"))
    val fallback = StubStrategy("cache")

    val error = assertThrowsNga { runStrategyChain(listOf(semantic, fallback), request, context()) }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals(0, fallback.calls)
  }

  @Test
  fun `全链失败时抛最后一个错误`() = runTest {
    val chain = listOf(
      StubStrategy("a", NgaError(NgaErrorKind.PARSE, "甲")),
      StubStrategy("b", NgaError(NgaErrorKind.NETWORK, "乙")),
    )

    val error = assertThrowsNga { runStrategyChain(chain, request, context()) }
    assertEquals("乙", error.text)
  }

  @Test
  fun `空链直接报错`() = runTest {
    val error = assertThrowsNga { runStrategyChain(emptyList(), request, context()) }
    assertEquals(NgaErrorKind.UNAVAILABLE, error.kind)
  }

  @Test
  fun `每一档的开始成功失败都发事件,方便排障`() = runTest {
    val events = mutableListOf<String>()
    val chain = listOf(
      StubStrategy("direct", NgaError(NgaErrorKind.PARSE, "x")),
      StubStrategy("cache"),
    )

    runStrategyChain(
      chain,
      request,
      FetchContext(
        transport = RecordingTransport { ok() },
        host = "https://bbs.nga.cn",
        authMode = AuthMode.NONE,
        credential = null,
        userAgents = UserAgents.fixed("w"),
        onEvent = { event ->
          when (event) {
            is FetchEvent.StrategyStart -> events += "strategy-start:${event.strategy}"
            is FetchEvent.StrategySuccess -> events += "strategy-success:${event.strategy}"
            is FetchEvent.StrategyFailure -> events += "strategy-failure:${event.strategy}"
            else -> Unit
          }
        },
      ),
    )

    assertEquals(
      listOf(
        "strategy-start:direct",
        "strategy-failure:direct",
        "strategy-start:cache",
        "strategy-success:cache",
      ),
      events,
    )
  }

  @Test
  fun `direct 策略可以和别的策略一起排进链里`() = runTest {
    val transport = RecordingTransport { NetFixture.NOTI_EMPTY.response() }
    val result = testClient(transport, strategies = listOf(DirectStrategy(), StubStrategy("cache")))
      .execute(readRequest("nuke.php"))

    assertEquals("direct", result.via)
  }

  // ── 引擎的两条附加性质(ADR-0002 第 5 条:可观测性是链的一部分) ────────────

  @Test
  fun `整条链失败时把 attempts 诊断挂到错误上`() = runTest {
    val transport = RecordingTransport { blocked() }
    val client = testClient(transport, strategies = listOf(DirectStrategy()))

    val error = assertThrowsNga { client.execute(readRequest("read.php", queryOf("tid" to 42))) }
    val diagnostic = error.diagnostic

    assertTrue(diagnostic != null, "整条链失败必须带诊断")
    assertEquals("read.php", diagnostic.path)
    assertEquals(mapOf("tid" to "42"), diagnostic.params)
    assertEquals(1, diagnostic.attempts.size)
    assertEquals("json", diagnostic.attempts[0].format)
    assertEquals("parse", diagnostic.attempts[0].error?.kind)
  }

  @Test
  fun `成功也留一条记录·组合、data 顶层键、条数,不含 cid 与正文`() = runTest {
    val transport = RecordingTransport {
      ok("""{"data":{"__T":{"0":{"tid":1},"1":{"tid":2}},"__F":{"fid":650}},"time":1}""")
    }
    val records = mutableListOf<FetchDiagnostic>()
    testClient(transport, onDiagnostic = records::add)
      .execute(readRequest("thread.php", queryOf("fid" to 650)))

    val success = records.single().success
    assertTrue(success != null)
    assertEquals("direct", success.strategy)
    assertEquals("json", success.format)
    assertEquals("https://bbs.nga.cn", success.host)
    assertEquals(listOf("__T", "__F"), success.keys)
    assertEquals(2, success.rows)
  }

  @Test
  fun `__output=11 的 __T 是真数组,条数照样数得出(ADR-0002 第 10 条)`() = runTest {
    // 不认数组的后果是整页主题静默变成 0 条,比抛错难查得多
    val transport = RecordingTransport {
      ok("""{"data":{"__T":[{"tid":1},{"tid":2},{"tid":3}]},"time":1}""")
    }
    val records = mutableListOf<FetchDiagnostic>()
    testClient(transport, onDiagnostic = records::add)
      .execute(readRequest("thread.php", queryOf("fid" to 650), format = ResponseFormat.JSON_VERBOSE))

    assertEquals(3, records.single().success?.rows)
  }

  @Test
  fun `unavailable 不盖掉更实质的错误`() = runTest {
    // 用户该看到的是「这一页被封了」,不是「没有可换的账号」
    val chain = listOf(
      StubStrategy("format-rotation", NgaError(NgaErrorKind.PARSE, "响应解析失败")),
      StubStrategy("switch-account", NgaError(NgaErrorKind.UNAVAILABLE, "只有一个已登录账号")),
    )

    val error = assertThrowsNga { runStrategyChain(chain, request, context()) }
    assertEquals(NgaErrorKind.PARSE, error.kind)
  }
}
