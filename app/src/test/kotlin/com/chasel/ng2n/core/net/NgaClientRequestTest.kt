package com.chasel.ng2n.core.net

import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NgaClientRequestTest {

  private fun paramsOfUrl(url: String): Map<String, String> =
    URI(url).rawQuery.orEmpty().split("&")
      .mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
      .toMap()

  @Test
  fun `自动带公共参数、格式参数,并剔除空值参数`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(transport).execute(
      readRequest("thread.php", queryOf("fid" to 650, "stid" to null, "page" to 1)),
    )

    val url = transport.requests[0].url
    assertTrue(url.startsWith("https://bbs.nga.cn/thread.php?"))
    val query = paramsOfUrl(url)
    assertEquals("UTF8", query["__inchst"])
    assertEquals("8", query["__output"])
    assertEquals("650", query["fid"])
    assertNull(query["stid"])
  }

  @Test
  fun `域名每次请求现取,设置页改完下一个请求就发到新域名`() = runTest {
    val transport = RecordingTransport { ok() }
    val settings = FakeSettings(hostValue = "https://ngabbs.com")
    val client = testClient(transport, settings = settings)

    client.execute(readRequest("thread.php", queryOf("fid" to 650)))
    settings.hostValue = "https://nga.178.com"
    client.execute(readRequest("thread.php", queryOf("fid" to 650)))

    assertEquals(
      listOf("https://ngabbs.com", "https://nga.178.com"),
      transport.requests.map(::originOf),
    )
  }

  @Test
  fun `按 format 换格式参数(反封锁链交替的就是这一维)`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(transport).execute(
      readRequest("nuke.php", queryOf("__lib" to "noti"), format = ResponseFormat.JSON_LITE),
    )

    assertTrue(transport.requests[0].url.contains("lite=js"))
    assertTrue(!transport.requests[0].url.contains("__output="))
  }

  @Test
  fun `GBK 参数按 GBK 编码进 query,并撤掉 inchst 声明`() = runTest {
    val transport = RecordingTransport { ok() }
    val client = testClient(transport)

    client.execute(readRequest("thread.php", queryOf("author" to gbk("原神"))))
    client.execute(readRequest("thread.php", queryOf("key" to "原神")))

    assertTrue(transport.requests[0].url.contains("author=%D4%AD%C9%F1"))
    assertTrue(!transport.requests[0].url.contains("__inchst"))
    assertTrue(transport.requests[1].url.contains("key=%E5%8E%9F%E7%A5%9E"))
    assertTrue(transport.requests[1].url.contains("__inchst=UTF8"))
  }

  @Test
  fun `表单里有 GBK 值时声明 charset 为 GBK`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(transport).execute(readRequest("nuke.php", form = queryOf("content" to gbk("原神"))))

    assertEquals(
      "application/x-www-form-urlencoded;charset=GBK",
      transport.requests[0].contentType,
    )
    assertEquals("content=%D4%AD%C9%F1", String(transport.requests[0].body!!, Charsets.ISO_8859_1))
  }

  @Test
  fun `form 认证配 GET 会明确报错,不静默降级成游客`() = runTest {
    val transport = RecordingTransport { ok() }
    val client = testClient(
      transport,
      credentials = FakeCredentials(signedIn = Credential("10000001", "fake-token")),
      authMode = AuthMode.FORM,
    )

    val error = assertThrowsNga { client.execute(readRequest("thread.php", method = HttpMethod.GET)) }
    assertEquals(NgaErrorKind.UNAVAILABLE, error.kind)
    assertEquals(0, transport.requests.size)
  }

  @Test
  fun `cookie 认证配 GET 正常`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(
      transport,
      credentials = FakeCredentials(signedIn = Credential("10000001", "fake-token")),
      authMode = AuthMode.COOKIE,
    ).execute(readRequest("thread.php", method = HttpMethod.GET))

    assertEquals(HttpMethod.GET, transport.requests[0].method)
    assertNull(transport.requests[0].body)
    assertEquals("10000001", transport.requests[0].credential?.uid)
  }

  @Test
  fun `带 UA 身份头与 Referer`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(transport, userAgents = UserAgents.fixed("SystemWebView-1.0"))
      .execute(readRequest("nuke.php"))

    assertEquals("SystemWebView-1.0", transport.requests[0].headers["User-Agent"])
    assertEquals("Nga_Official", transport.requests[0].headers["X-User-Agent"])
    assertEquals("https://bbs.nga.cn/", transport.requests[0].headers["Referer"])
  }

  @Test
  fun `read_php 可切 Windows Phone UA`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(transport).execute(
      readRequest("read.php", userAgent = UserAgentProfile.WINDOWS_PHONE),
    )

    assertEquals("NGA_WP_JW/(;WINDOWS)", transport.requests[0].headers["User-Agent"])
  }

  @Test
  fun `默认 POST,业务参数在 query、认证在 body`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(
      transport,
      credentials = FakeCredentials(signedIn = Credential("10000001", "fake-token")),
      authMode = AuthMode.FORM,
    ).execute(readRequest("thread.php", queryOf("fid" to 650)))

    assertEquals(HttpMethod.POST, transport.requests[0].method)
    assertEquals(
      "access_uid=10000001&access_token=fake-token",
      String(transport.requests[0].body!!, Charsets.ISO_8859_1),
    )
    assertEquals("application/x-www-form-urlencoded", transport.requests[0].contentType)
  }

  @Test
  fun `cookie 认证方式把凭证放 cookie 通道,body 不带凭证`() = runTest {
    val transport = RecordingTransport { ok() }
    testClient(
      transport,
      credentials = FakeCredentials(signedIn = Credential("10000001", "fake-token")),
      authMode = AuthMode.COOKIE,
    ).execute(readRequest("nuke.php"))

    assertEquals(
      Credential("10000001", "fake-token"),
      transport.requests[0].credential,
    )
    assertEquals("", String(transport.requests[0].body!!, Charsets.ISO_8859_1))
  }

  @Test
  fun `单条请求可覆盖账号(反封锁链换账号重试要用)`() = runTest {
    val transport = RecordingTransport { ok() }
    val client = testClient(
      transport,
      credentials = FakeCredentials(signedIn = Credential("1", "a")),
      authMode = AuthMode.COOKIE,
    )

    client.execute(readRequest("nuke.php", credential = CredentialOverride(Credential("2", "b"))))
    client.execute(readRequest("nuke.php", credential = CredentialOverride.GUEST))

    assertEquals("2", transport.requests[0].credential?.uid)
    assertNull(transport.requests[1].credential)
  }
}
