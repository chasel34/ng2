package com.chasel.ng2n.data

import android.content.Context
import com.chasel.ng2n.core.local.ImageSize
import com.chasel.ng2n.core.local.ImageSizeStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class FileImageSizeStore @Inject constructor(
  @ApplicationContext private val context: Context,
) : ImageSizeStore {

  @Serializable
  private data class Row(val u: String, val w: Int, val h: Int)

  private val json = Json { ignoreUnknownKeys = true }

  private val file: File
    get() = File(context.filesDir, FILE_NAME)

  override suspend fun load(): List<Pair<String, ImageSize>> = withContext(Dispatchers.IO) {
    val target = file
    if (!target.exists()) return@withContext emptyList()
    runCatching {
      json.decodeFromString<List<Row>>(target.readText())
        .map { it.u to ImageSize(it.w, it.h) }
    }.getOrElse { emptyList() }
  }

  override suspend fun save(entries: List<Pair<String, ImageSize>>) = withContext(Dispatchers.IO) {
    val payload = json.encodeToString(entries.map { Row(it.first, it.second.width, it.second.height) })
    runCatching {
      val temp = File(context.filesDir, "$FILE_NAME.tmp")
      temp.writeText(payload)
      if (!temp.renameTo(file)) {
        file.writeText(payload)
        temp.delete()
      }
    }
    Unit
  }

  private companion object {
    const val FILE_NAME = "image-sizes.v1.json"
  }
}
