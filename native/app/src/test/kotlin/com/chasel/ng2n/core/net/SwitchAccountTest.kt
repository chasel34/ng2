package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.SwitchAccountStrategy
import com.chasel.ng2n.core.net.strategies.nextCredentialAfter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐条移植自 `src/core/net/strategies/switch-account.test.ts`
 * (`nextCredentialsAfter` 3 条 + `换账号重试` 5 条 = 8 条,全部移植)。
 */
class SwitchAccountTest {

  private fun uidOf(request: HttpRequest) = request.credential?.uid

  /** 只有 Bob 的凭证能拿到数据,Alice 一律被封。 */
  private fun onlyBobWorks() =
    RecordingTransport { if (uidOf(it) == BOB.uid) ok() else blocked() }

  private fun chainClient(
    transport: Transport,
    accounts: List<Credential>,
    cache: ComboCache = InMemoryComboCache(),
  ) = testClient(
    transport,
    strategies = listOf(
      FormatRotationStrategy(listOf(ResponseFormat.JSON), listOf("https://bbs.nga.cn")),
      SwitchAccountStrategy { accounts },
    ),
    credentials = FakeCredentials(signedIn = accounts.firstOrNull(), accounts = accounts),
    comboCache = cache,
  )

  // ── nextCredentialAfter ───────────────────────────────────────────────────

  @Test
  fun `取当前账号之后的下一个,循环`() {
    assertEquals(BOB, nextCredentialAfter(listOf(ALICE, BOB), ALICE))
    assertEquals(ALICE, nextCredentialAfter(listOf(ALICE, BOB), BOB))
  }

  @Test
  fun `不足两个账号就没得换`() {
    assertNull(nextCredentialAfter(emptyList(), null))
    assertNull(nextCredentialAfter(listOf(ALICE), ALICE))
  }

  @Test
  fun `当前是游客(或刚退出登录)时从头一个开始`() {
    assertEquals(ALICE, nextCredentialAfter(listOf(ALICE, BOB), null))
    assertEquals(ALICE, nextCredentialAfter(listOf(ALICE, BOB), Credential("999", "x")))
  }

  // ── 换账号重试 ─────────────────────────────────────────────────────────────

  @Test
  fun `多账号时取下一个账号的 cookie 重试,成了就用它的结果`() = runTest {
    val transport = onlyBobWorks()

    val result = chainClient(transport, listOf(ALICE, BOB)).execute(readRequest("thread.php"))

    assertEquals("switch-account", result.via)
    assertEquals(listOf(ALICE.uid, BOB.uid), transport.uids())
  }

  @Test
  fun `只有一个账号时这一档不启用,一次请求都不发`() = runTest {
    val transport = onlyBobWorks()

    val error = assertThrowsNga {
      chainClient(transport, listOf(ALICE)).execute(readRequest("thread.php"))
    }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    // 只有 format-rotation 那一档发过请求,全是 Alice
    assertEquals(listOf(ALICE.uid), transport.uids())
  }

  @Test
  fun `只试一次·换的那个账号也被封就交给链上后面的兜底,不再换第三个`() = runTest {
    val transport = RecordingTransport { blocked() }

    val error = assertThrowsNga {
      chainClient(transport, listOf(ALICE, BOB, CAROL)).execute(readRequest("thread.php"))
    }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(listOf(ALICE.uid, BOB.uid), transport.uids())
  }

  @Test
  fun `沿用缓存里的成功组合·这一档变的是身份,不是组合`() = runTest {
    val cache = InMemoryComboCache()
    cache.remember("thread.php", FetchCombo(ResponseFormat.JSON_LITE, "https://ngabbs.com"))
    val transport = onlyBobWorks()
    val client = testClient(
      transport,
      strategies = listOf(SwitchAccountStrategy { listOf(ALICE, BOB) }),
      credentials = FakeCredentials(signedIn = ALICE, accounts = listOf(ALICE, BOB)),
      comboCache = cache,
    )

    client.execute(readRequest("thread.php"))

    assertEquals(1, transport.requests.size)
    assertTrue(transport.requests[0].url.startsWith("https://ngabbs.com/thread.php"))
    assertTrue(transport.requests[0].url.contains("lite=js"))
  }

  @Test
  fun `业务错误照样不重试·换个账号也还是同一个语义错误`() = runTest {
    val transport = RecordingTransport { ok("""{"error":{"0":"2048:找不到主题"}}""") }

    val error = assertThrowsNga {
      chainClient(transport, listOf(ALICE, BOB))
        .execute(readRequest("read.php", queryOf("tid" to 1)))
    }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals(1, transport.requests.size)
  }
}
