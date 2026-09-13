package com.chasel.ng2n.data.updates

import com.chasel.ng2n.core.updates.UpdateManifest
import com.chasel.ng2n.core.updates.validateUpdateManifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GithubReleaseClientTest {
  private val manifest = UpdateManifest("0.2.2", 5, "com.chasel.ng2", "ng2-v0.2.2.apk", "a".repeat(64), 1234)
  private val prefix = "https://github.com/chasel34/ng2/releases/download/v0.2.2/"
  private val release = """{"tag_name":"v0.2.2","body":"更新说明","assets":[{"name":"update.json","browser_download_url":"${prefix}update.json"},{"name":"ng2-v0.2.2.apk","browser_download_url":"${prefix}ng2-v0.2.2.apk"}]}"""

  private fun client(payload: String = release, metadata: UpdateManifest = manifest, code: Int = 200): GithubReleaseClient {
    val http = OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      assertNull(request.header("Cookie"))
      val api = request.url.host == "api.github.com"
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (api) code else 200).message("test")
        .body((if (api) payload else Json.encodeToString(metadata)).toResponseBody()).build()
    }.build()
    return GithubReleaseClient(http)
  }

  @Test fun `newer code returns release notes and apk`() = runTest {
    val result = client().check(4, "com.chasel.ng2")!!
    assertEquals(5, result.manifest.versionCode)
    assertEquals("更新说明", result.notes)
    assertEquals("${prefix}ng2-v0.2.2.apk", result.apkUrl)
  }

  @Test fun `same and older code do not offer update`() = runTest {
    assertNull(client().check(5, "com.chasel.ng2"))
    assertNull(client().check(6, "com.chasel.ng2"))
  }

  @Test fun `no published releases and legacy release are supported`() = runTest {
    assertNull(client(code = 404).check(4, "com.chasel.ng2"))
    assertNull(client("""{"tag_name":"v0.2.0"}""").check(4, "com.chasel.ng2"))
  }

  @Test fun `draft and prerelease do not offer updates`() = runTest {
    for (flag in listOf("draft", "prerelease")) {
      assertNull(client(release.replace("{\"tag_name\"", "{\"$flag\":true,\"tag_name\"")).check(4, "com.chasel.ng2"))
    }
  }

  @Test fun `rate limits are errors rather than up to date`() = runTest {
    assertFailsWith<java.io.IOException> { client(code = 403).check(4, "com.chasel.ng2") }
    assertFailsWith<java.io.IOException> { client(code = 429).check(4, "com.chasel.ng2") }
  }

  @Test fun `missing manifest or apk is rejected`() = runTest {
    assertFailsWith<IllegalArgumentException> { client("""{"tag_name":"v0.2.2"}""").check(4, "com.chasel.ng2") }
    assertFailsWith<IllegalArgumentException> {
      client(release.replace("\"name\":\"ng2-v0.2.2.apk\"", "\"name\":\"other.apk\"")).check(4, "com.chasel.ng2")
    }
  }

  @Test fun `foreign downloads and wrong packages are rejected`() = runTest {
    assertFailsWith<IllegalArgumentException> { client(release.replace("github.com/chasel34", "github.com/other")).check(4, "com.chasel.ng2") }
    assertFailsWith<IllegalArgumentException> { client().check(4, "com.chasel.ng2.dev") }
  }

  @Test fun `manifest rejects bad hash size path and mismatched version`() {
    for (invalid in listOf(
      manifest.copy(sha256 = "bad"), manifest.copy(size = 0), manifest.copy(size = 200_000_001),
      manifest.copy(apkName = "../app.apk"), manifest.copy(versionName = "0.2.1"), manifest.copy(versionCode = 0),
    )) assertFailsWith<IllegalArgumentException> { validateUpdateManifest(invalid, "v0.2.2", "com.chasel.ng2") }
  }
}
