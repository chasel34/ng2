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

@Singleton
class ImageSaver @Inject constructor(
  @ApplicationContext private val context: Context,
  private val clientProvider: Provider<OkHttpClient>,
  private val attachmentUrls: AttachmentUrls,
) {

  private val albumRelativePath = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

  enum class SaveOutcome { SAVED, DUPLICATE }

  data class BatchSaveResult(val saved: Int, val skipped: Int, val failed: Int)

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

  suspend fun saveToAlbum(url: String): SaveOutcome = withContext(Dispatchers.IO) {
    val name = attachmentUrls.imageFileName(url)
    if (albumContains(name)) return@withContext SaveOutcome.DUPLICATE
    insert(download(url), name)
    SaveOutcome.SAVED
  }

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
    ContentUris.parseId(uri)
    return uri
  }

  private companion object {
    const val ALBUM_NAME = "NGA"
    const val CACHE_DIR = "ng2n-images"
  }
}
