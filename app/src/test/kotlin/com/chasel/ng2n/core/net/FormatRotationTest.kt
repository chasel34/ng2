package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐条移植自 `src/core/net/strategies/format-rotation.test.ts`
 * (`格式参数 × 域名的组合枚举` 11 条 + `服务端说「未登录」` 5 条 = 16 条,全部移植)。
 */
class FormatRotationTest {

  private val hosts = listOf("https://bbs.nga.cn", "https://ngabbs.com")
  private val formats = listOf(ResponseFormat.JSON, ResponseFormat.JSON_LITE)

  private fun rotating(
    transport: Transport,
    cache: ComboCache = InMemoryComboCache(),
    credentials: FakeCredentials = FakeCredentials(),
    maxAttempts: Int = 10,
  ) = testClient(
    transport,
    strategies = listOf(FormatRotationStrategy(formats, hosts, maxAttempts)),
    comboCache = cache,
    credentials = credentials,
    settings = FakeSettings(hostValue = hosts[0]),
  )

  @Test
  fun `封禁响应触发按序降级,直到某个组合通了`() = runTest {
    val transport = onlyWorking("lite=js@https://ngabbs.com")

    val result = rotating(transport).execute(readRequest("thread.php", queryOf("fid" to 650)))

    assertEquals("format-rotation", result.via)
    assertEquals(
      listOf(
        "__output=8@https://bbs.nga.cn",
        "lite=js@https://bbs.nga.cn",
        "__output=8@https://ngabbs.com",
        "lite=js@https://ngabbs.com",
      ),
      transport.combos(),
    )
  }

  @Test
  fun `成功的组合按接口 key 记进缓存,下一次直接命中不再从头试`() = runTest {
    val cache = InMemoryComboCache()
    val transport = onlyWorking("lite=js@https://ngabbs.com")
    val client = rotating(transport, cache)
    val request = readRequest("thread.php", queryOf("fid" to 650))

    client.execute(request)
    assertEquals(
      FetchCombo(ResponseFormat.JSON_LITE, "https://ngabbs.com"),
      cache.get(interfaceKeyOf(request)),
    )

    transport.clear()
    client.execute(request)
    assertEquals(listOf("lite=js@https://ngabbs.com"), transport.combos())
  }

  @Test
  fun `缓存是按接口分的·另一个接口还得自己从头试`() = runTest {
    val cache = InMemoryComboCache()
    val transport = onlyWorking("lite=js@https://bbs.nga.cn")
    val client = rotating(transport, cache)

    client.execute(readRequest("nuke.php", queryOf("__lib" to "noti", "__act" to "get_all")))
    transport.clear()
    client.execute(readRequest("nuke.php", queryOf("__lib" to "ucp", "__act" to "get")))

    assertEquals(
      listOf("__output=8@https://bbs.nga.cn", "lite=js@https://bbs.nga.cn"),
      transport.combos(),
    )
  }

  @Test
  fun `全组合都被封时,缓存里那个也清掉,免得下次还从它开局`() = runTest {
    val cache = InMemoryComboCache()
    val request = readRequest("thread.php", queryOf("fid" to 650))
    rotating(onlyWorking("lite=js@https://bbs.nga.cn"), cache).execute(request)
    assertNotNull(cache.get(interfaceKeyOf(request)))

    val allBlocked = RecordingTransport { blocked() }
    val error = assertThrowsNga { rotating(allBlocked, cache).execute(request) }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertNull(cache.get(interfaceKeyOf(request)))
    assertEquals(4, allBlocked.requests.size)
  }

  @Test
  fun `业务错误(权限不足)不触发降级·那说明这个组合根本没被封`() = runTest {
    val transport = RecordingTransport {
      FakeResponse(
        status = 403,
        body = utf8("""{"error":{"code":8,"0":"您没有浏览该版面的权限"}}"""),
      )
    }

    val error = assertThrowsNga {
      rotating(transport).execute(readRequest("thread.php", queryOf("fid" to 650)))
    }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertTrue(error.text.contains("权限"), error.text)
    assertEquals(1, transport.requests.size)
  }

  @Test
  fun `业务错误也算这个组合是通的,一样记进缓存`() = runTest {
    val cache = InMemoryComboCache()
    val transport = RecordingTransport {
      if (comboOf(it) == "lite=js@https://bbs.nga.cn") {
        ok("""{"error":{"0":"2048:找不到主题"}}""")
      } else {
        blocked()
      }
    }
    val request = readRequest("read.php", queryOf("tid" to 1))

    val error = assertThrowsNga { rotating(transport, cache).execute(request) }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals(
      FetchCombo(ResponseFormat.JSON_LITE, "https://bbs.nga.cn"),
      cache.get(interfaceKeyOf(request)),
    )
  }

  @Test
  fun `组合数上限生效,不会让人等十几个来回`() = runTest {
    val transport = RecordingTransport { blocked() }
    val client = testClient(
      transport,
      strategies = listOf(FormatRotationStrategy(maxAttempts = 3)),
      settings = FakeSettings(hostValue = hosts[0]),
    )

    val error = assertThrowsNga { client.execute(readRequest("thread.php")) }
    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(3, transport.requests.size)
  }

  @Test
  fun `每次重试前重建 HTTP client(第一次用现成的)`() = runTest {
    val transport = onlyWorking("lite=js@https://ngabbs.com")
    val factory = CountingTransportFactory(transport)
    val client = testClient(
      transport,
      transports = factory,
      strategies = listOf(FormatRotationStrategy(formats, hosts, maxAttempts = 10)),
      settings = FakeSettings(hostValue = hosts[0]),
    )

    client.execute(readRequest("thread.php"))

    // 建 context 时一次 + 第 2/3/4 次尝试各重建一次
    assertEquals(4, factory.built)
  }

  @Test
  fun `调用方指定的域名排第一,被封了照样往下轮换`() = runTest {
    val transport = onlyWorking("__output=8@https://bbs.nga.cn")

    rotating(transport).execute(readRequest("thread.php", host = "https://nga.178.com"))

    assertEquals("__output=8@https://nga.178.com", transport.combos().first())
    assertEquals("__output=8@https://bbs.nga.cn", transport.combos().last())
  }

  @Test
  fun `取消请求不当被封,一次就停`() = runTest {
    // TS 版是「network / retryable=false」;Kotlin 走协程取消,原样抛
    // (见 NgaClientResponseTest 同名用例的注释)。「一次就停」这一半不变。
    var attempts = 0
    val transport = Transport {
      attempts += 1
      throw kotlinx.coroutines.CancellationException("Aborted")
    }
    var cancelled = false
    try {
      rotating(transport).execute(readRequest("thread.php"))
    } catch (_: kotlinx.coroutines.CancellationException) {
      cancelled = true
    }

    assertTrue(cancelled)
    assertEquals(1, attempts)
  }

  @Test
  fun `缓存里那个组合当场失手就先摘掉(自愈)`() = runTest {
    // ADR-0002 第 2 条:组合半通不通时缓存要有出口,不能只靠「全组合都失败」
    val cache = InMemoryComboCache()
    cache.remember("thread.php", FetchCombo(ResponseFormat.JSON_LITE, "https://ngabbs.com"))
    val transport = onlyWorking("__output=8@https://bbs.nga.cn")

    rotating(transport, cache).execute(readRequest("thread.php"))

    assertEquals(
      FetchCombo(ResponseFormat.JSON, "https://bbs.nga.cn"),
      cache.get("thread.php"),
    )
    assertEquals("lite=js@https://ngabbs.com", transport.combos().first())
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 服务端说「未登录」:它长得像服务端语义错误,实际是这一发请求没带上身份。
  // 真机取证(2026-08-13,小米 25113PN0EC):冷启动后第一个版块约 1/6 概率直接报
  // 「1:未登录」,手点重试(= 忘掉组合重来)立刻就好 —— 因为 cookie jar 按域名存,
  // 换个域名我们自己拼的 Cookie 头就不会被顶掉。
  // ────────────────────────────────────────────────────────────────────────────

  private val unauthed = """{"error":{"code":1,"0":"未登录"},"time":1}"""

  private fun onlyAuthed(only: String) =
    RecordingTransport { if (comboOf(it) == only) ok() else ok(unauthed) }

  @Test
  fun `手上有凭证时继续换组合,直到某个域名认出身份`() = runTest {
    val transport = onlyAuthed("lite=js@https://ngabbs.com")
    val client = rotating(transport, credentials = FakeCredentials(signedIn = ALICE))

    val result = client.execute(readRequest("thread.php", queryOf("fid" to 650)))

    assertEquals("format-rotation", result.via)
    assertEquals(
      listOf(
        "__output=8@https://bbs.nga.cn",
        "lite=js@https://bbs.nga.cn",
        "__output=8@https://ngabbs.com",
        "lite=js@https://ngabbs.com",
      ),
      transport.combos(),
    )
  }

  @Test
  fun `丢身份的那个组合不会被记进缓存,下一次不从它开局`() = runTest {
    val cache = InMemoryComboCache()
    val transport = onlyAuthed("lite=js@https://ngabbs.com")
    val client = rotating(transport, cache, FakeCredentials(signedIn = ALICE))
    val request = readRequest("thread.php", queryOf("fid" to 650))

    client.execute(request)
    assertEquals(
      FetchCombo(ResponseFormat.JSON_LITE, "https://ngabbs.com"),
      cache.get(interfaceKeyOf(request)),
    )

    transport.clear()
    client.execute(request)
    assertEquals(listOf("lite=js@https://ngabbs.com"), transport.combos())
  }

  @Test
  fun `游客态不换组合·哪个域名都没 cookie,白跑一整轮只会把错误页拖慢`() = runTest {
    val transport = onlyAuthed("lite=js@https://ngabbs.com")

    val error = assertThrowsNga {
      rotating(transport).execute(readRequest("thread.php", queryOf("fid" to 650)))
    }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals("未登录", error.text)
    assertEquals(listOf("__output=8@https://bbs.nga.cn"), transport.combos())
  }

  /**
   * 但「不换组合」不等于「掐死整条链」。真机取证(2026-08-13):游客态打开帖子,
   * 直连报未登录,而点「用网页版打开」正文完整渲染 —— 网页兜底本来就能拿到这一页,
   * 只是以前错误被判成不可重试,`runStrategyChain` 在轮到它之前就抛了。
   */
  @Test
  fun `游客态的未登录仍然可重试,好让链上后面的网页兜底接手`() = runTest {
    val transport = onlyAuthed("(没有能用的组合)")
    val rescue = StubStrategy("web-fallback")
    val client = testClient(
      transport,
      strategies = listOf(FormatRotationStrategy(formats, hosts, maxAttempts = 10), rescue),
      settings = FakeSettings(hostValue = hosts[0]),
    )

    val result = client.execute(readRequest("read.php", queryOf("tid" to 1)))

    assertEquals("web-fallback", result.via)
    // 只发了一次直连就让位给兜底,没有白跑一整轮组合
    assertEquals(listOf("__output=8@https://bbs.nga.cn"), transport.combos())
  }

  @Test
  fun `所有组合都说未登录时,报给用户的仍是服务端原话`() = runTest {
    val transport = onlyAuthed("(没有能用的组合)")

    val error = assertThrowsNga {
      rotating(transport, credentials = FakeCredentials(signedIn = ALICE))
        .execute(readRequest("thread.php", queryOf("fid" to 650)))
    }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals("未登录", error.text)
    assertEquals(4, transport.requests.size)
  }
}
