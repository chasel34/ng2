package com.chasel.ng2n.core.updates

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
  val versionName: String,
  val versionCode: Long,
  val applicationId: String,
  val apkName: String,
  val sha256: String,
  val size: Long,
)

@Serializable
data class ReleaseUpdate(val manifest: UpdateManifest, val apkUrl: String, val notes: String)

fun validateUpdateManifest(manifest: UpdateManifest, tag: String, applicationId: String) {
  require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) { "发布版本格式无效" }
  require(manifest.versionName == tag.removePrefix("v")) { "更新版本信息不一致" }
  require(manifest.applicationId == applicationId) { "更新包与当前应用不匹配" }
  require(manifest.versionCode > 0) { "更新版本代码无效" }
  require(manifest.apkName == "ng2-$tag.apk") { "更新文件名无效" }
  require(Regex("[a-f0-9]{64}").matches(manifest.sha256)) { "更新校验信息无效" }
  require(manifest.size in 1..200_000_000L) { "更新文件大小无效" }
}
