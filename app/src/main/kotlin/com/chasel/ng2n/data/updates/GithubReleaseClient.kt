package com.chasel.ng2n.data.updates

import com.chasel.ng2n.core.updates.ReleaseUpdate
import com.chasel.ng2n.core.updates.UpdateManifest
import com.chasel.ng2n.core.updates.validateUpdateManifest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class ReleaseAsset(val name: String, @SerialName("browser_download_url") val url: String)

@Serializable
internal data class GithubRelease(
  @SerialName("tag_name") val tag: String,
  val draft: Boolean = false,
  val prerelease: Boolean = false,
  val assets: List<ReleaseAsset> = emptyList(),
  val body: String? = null,
)

@Singleton
class GithubReleaseClient internal constructor(private val client: OkHttpClient) {
  // NGA 的 CookieJar 会给请求目标附加账号凭证，更新请求必须使用独立客户端。
  @Inject constructor() : this(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build())
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun check(currentCode: Long, applicationId: String): ReleaseUpdate? = withContext(Dispatchers.IO) {
    val payload = get("https://api.github.com/repos/chasel34/ng2/releases/latest", allowMissing = true)
      ?: return@withContext null
    val release = json.decodeFromString<GithubRelease>(payload)
    if (release.draft || release.prerelease) return@withContext null
    val asset = release.assets.singleOrNull { it.name == "update.json" }
    // 首个公开版本早于更新协议，之后的发布都必须附带更新清单。
    if (asset == null && release.tag == "v0.2.0") return@withContext null
    requireNotNull(asset) { "发布缺少更新信息，请稍后重试" }
    val prefix = "https://github.com/chasel34/ng2/releases/download/${release.tag}/"
    require(asset.url == "${prefix}update.json") { "更新信息地址无效" }
    val manifest = json.decodeFromString<UpdateManifest>(get(asset.url)!!)
    validateUpdateManifest(manifest, release.tag, applicationId)
    if (manifest.versionCode <= currentCode) return@withContext null
    val apk = release.assets.singleOrNull { it.name == manifest.apkName }
    require(apk?.url == "$prefix${manifest.apkName}") { "发布缺少匹配的安装包" }
    ReleaseUpdate(manifest, apk.url, release.body.orEmpty())
  }

  private fun get(url: String, allowMissing: Boolean = false): String? {
    val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
      .header("User-Agent", "NG2-Update").header("Cache-Control", "no-cache").build()
    return client.newCall(request).execute().use { response ->
      if (allowMissing && response.code == 404) return@use null
      if (!response.isSuccessful) throw IOException("检查更新失败（HTTP ${response.code}）")
      val source = response.body.source()
      source.request(1_000_001L)
      val bytes = source.readByteArray(minOf(source.buffer.size, 1_000_001L))
      require(bytes.size <= 1_000_000) { "更新信息过大" }
      bytes.toString(Charsets.UTF_8)
    }
  }
}
