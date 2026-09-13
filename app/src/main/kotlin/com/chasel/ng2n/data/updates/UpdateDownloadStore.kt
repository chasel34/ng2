package com.chasel.ng2n.data.updates

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.chasel.ng2n.core.updates.ReleaseUpdate
import com.chasel.ng2n.core.updates.validateUpdateManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class SavedDownload(val id: Long, val update: ReleaseUpdate)
data class DownloadProgress(val complete: Boolean, val bytes: Long, val total: Long, val waiting: Boolean)

@Singleton
class UpdateDownloadStore @Inject constructor(@ApplicationContext private val context: Context) {
  private val manager = context.getSystemService(DownloadManager::class.java)
  private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun restore(): SavedDownload? = withContext(Dispatchers.IO) {
    val id = preferences.getLong("download_id", -1)
    val payload = preferences.getString("update", null)
    if (id < 0 || payload == null) return@withContext null
    val update = runCatching { json.decodeFromString<ReleaseUpdate>(payload) }.getOrNull()
    if (update == null) {
      manager.remove(id)
      preferences.edit().clear().commit()
      return@withContext null
    }
    SavedDownload(id, update)
  }

  suspend fun start(update: ReleaseUpdate): SavedDownload = withContext(Dispatchers.IO) {
    restore()?.let { cancel(it) }
    val target = file(update)
    if (target.exists()) check(target.delete()) { "无法清理旧安装包" }
    val request = DownloadManager.Request(Uri.parse(update.apkUrl))
      .setTitle("NG2 ${update.manifest.versionName}")
      .setDescription("正在下载应用更新")
      .setMimeType("application/vnd.android.package-archive")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationInExternalFilesDir(context, "updates", update.manifest.apkName)
    val id = manager.enqueue(request)
    if (!preferences.edit().putLong("download_id", id).putString("update", json.encodeToString(update)).commit()) {
      manager.remove(id)
      error("无法保存下载状态")
    }
    SavedDownload(id, update)
  }

  suspend fun progress(download: SavedDownload): DownloadProgress = withContext(Dispatchers.IO) {
    manager.query(DownloadManager.Query().setFilterById(download.id)).use { cursor ->
      check(cursor.moveToFirst()) { "下载任务已移除，请重试" }
      fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
      val status = long(DownloadManager.COLUMN_STATUS).toInt()
      check(status != DownloadManager.STATUS_FAILED) {
        "下载失败（${long(DownloadManager.COLUMN_REASON)}），请重试"
      }
      DownloadProgress(
        complete = status == DownloadManager.STATUS_SUCCESSFUL,
        bytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
        total = download.update.manifest.size,
        waiting = status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING,
      )
    }
  }

  suspend fun cancel(download: SavedDownload) = withContext(Dispatchers.IO) {
    manager.remove(download.id)
    file(download.update).delete()
    preferences.edit().clear().commit()
    Unit
  }

  suspend fun verifiedUri(download: SavedDownload): Uri = withContext(Dispatchers.IO) {
    val manifest = download.update.manifest
    validateUpdateManifest(manifest, "v${manifest.versionName}", context.packageName)
    val apk = file(download.update)
    check(apk.isFile && apk.length() == manifest.size) { "安装包大小不匹配，请重新下载" }
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) {
      "安装包校验失败，请重新下载"
    }
    val pm = context.packageManager
    val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
    check(archive != null && archive.packageName == context.packageName &&
      archive.longVersionCode == manifest.versionCode && archive.versionName == manifest.versionName &&
      archive.longVersionCode > installed.longVersionCode) { "安装包版本不匹配" }
    val currentSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
    val incoming = archive.signingInfo
    val compatible = if (incoming?.hasMultipleSigners() == true || currentSigners.size > 1) {
      incoming?.apkContentsSigners?.toSet() == currentSigners
    } else {
      incoming?.signingCertificateHistory?.toSet().orEmpty().containsAll(currentSigners)
    }
    check(currentSigners.isNotEmpty() && compatible) { "安装包签名不兼容，无法保留数据升级" }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
  }

  private fun file(update: ReleaseUpdate): File {
    validateUpdateManifest(update.manifest, "v${update.manifest.versionName}", context.packageName)
    return File(checkNotNull(context.getExternalFilesDir("updates")) { "下载目录不可用" }, update.manifest.apkName)
  }
}
