package com.chasel.ng2n.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.chasel.ng2n.core.api.AttachmentUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 大图查看器的落盘动作:保存到相册、系统分享、批量下载(RN 侧原件 `src/ui/image-files.ts`)。
 *
 * 相册与分享吃的都是本地文件,所以先把原图下到缓存目录中转;文件名由
 * [AttachmentUrls.imageFileName] 从 URL **稳定**推出,同一张图重复保存不会在缓存里
 * 越积越多,也让「这张存过没有」变成一次文件名比对(RN 侧 M4 验收 G8)。
 *
 * 下载走注入的 [OkHttpClient] —— 与图片管线、协议层是**同一个** client(票 06 会替换它的
 * 构造),附件域名要登录态的场景才不豆腐。
 */
@Singleton
class ImageSaver @Inject constructor(
  @ApplicationContext private val context: Context,
  private val clientProvider: Provider<OkHttpClient>,
  private val attachmentUrls: AttachmentUrls,
) {

  /** 设计稿 toast 说的「相册/NGA」:保存的目标相册名。 */
  private val albumRelativePath = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

  enum class SaveOutcome { SAVED, DUPLICATE }

  data class BatchSaveResult(val saved: Int, val skipped: Int, val failed: Int)

  /**
   * 把原图下到缓存目录,返回本地文件。已存在的直接复用(Coil 的磁盘缓存不暴露路径,
   * 而且那里存的是它自己的键名,拿不到能给系统分享用的文件名)。
   */
  suspend fun download(url: String): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    val file = File(directory, attachmentUrls.imageFileName(url))
    if (file.exists() && file.length() > 0L) return@withContext file
    if (file.exists()) file.delete()

    val request = Request.Builder().url(url).build()
    clientProvider.get().newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("图片下载失败(HTTP ${response.code})")
      file.outputStream().use { out -> response.body.byteStream().copyTo(out) }
    }
    if (file.length() <= 0L) {
      file.delete()
      error("图片下载失败(0 字节)")
    }
    file
  }

  /**
   * 把一张图存进「相册/NGA」;已经存过的不再存第二份。
   *
   * **对 RN 版的改进**:RN 侧走 expo-media-library,`Asset.create` 统一按「有没有授权」
   * 把关,于是每次保存都要先弹一次运行时权限。原生这边 minSdk 31,往 MediaStore 写
   * **自己创建的**图片不需要任何运行时权限(分区存储),所以整条权限路径去掉了 —— 少一个弹窗。
   */
  suspend fun saveToAlbum(url: String): SaveOutcome = withContext(Dispatchers.IO) {
    val name = attachmentUrls.imageFileName(url)
    if (albumContains(name)) return@withContext SaveOutcome.DUPLICATE
    insert(download(url), name)
    SaveOutcome.SAVED
  }

  /**
   * 批量下载进相册。顺序下,一张失败不拦着后面的;已经在相册里的跳过。
   * 相册里已有的文件名**只查一次**,批内新存进去的补进同一个集合。
   */
  suspend fun saveAllToAlbum(urls: List<String>): BatchSaveResult = withContext(Dispatchers.IO) {
    val existing = albumFilenames().toMutableSet()
    var saved = 0
    var skipped = 0
    var failed = 0
    for (url in urls) {
      val name = attachmentUrls.imageFileName(url)
      if (name in existing) {
        skipped += 1
        continue
      }
      val outcome = runCatching { insert(download(url), name) }
      if (outcome.isSuccess) {
        existing += name
        saved += 1
      } else {
        failed += 1
      }
    }
    BatchSaveResult(saved, skipped, failed)
  }

  /** 系统分享面板分享**图片文件本体**(不是分享一条链接)。 */
  suspend fun shareIntent(url: String): Intent {
    val file = download(url)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
      type = attachmentUrls.imageMimeType(file.name)
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  /**
   * 相册里已有的文件名。文件名从 URL 稳定推出,所以「同名」就是「同一张图存过了」。
   * MediaStore 的 `RELATIVE_PATH` 存的是带结尾斜杠的形式。
   */
  private fun albumFilenames(): Set<String> {
    val names = mutableSetOf<String>()
    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    runCatching {
      context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        arrayOf("$albumRelativePath/"),
        null,
      )?.use { cursor ->
        val column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        while (cursor.moveToNext()) names += cursor.getString(column)
      }
    }
    return names
  }

  private fun albumContains(name: String): Boolean = name in albumFilenames()

  /** 写进 `Pictures/NGA`。IS_PENDING 期间对别的 app 不可见,写完才落定。 */
  private fun insert(file: File, name: String): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, attachmentUrls.imageMimeType(name))
      put(MediaStore.MediaColumns.RELATIVE_PATH, albumRelativePath)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: error("相册写入失败")
    runCatching {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        ?: error("相册写入失败")
    }.onFailure {
      resolver.delete(uri, null, null)
      throw it
    }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    // 拿 id 只为让调用方(将来)能直接跳相册;这里保持返回值稳定
    ContentUris.parseId(uri)
    return uri
  }

  private companion object {
    const val ALBUM_NAME = "NGA"
    const val CACHE_DIR = "ng2n-images"
  }
}
