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

/**
 * 图片尺寸记忆表的磁盘持久层:`filesDir/image-sizes.v1.json` 一个文件全量覆盖。
 *
 * **为什么是裸 JSON 文件而不是 DataStore**(票里留白的「实现时定」):
 * 1. 这份数据是**纯缓存**,丢了只是「下次启动第一遍图会跳一下比例」,不需要
 *    DataStore 的事务保证与损坏恢复;
 * 2. DataStore 的装配归票 14,票 12 用它就得先把那套接线抢着做一半,合并时反而打架;
 * 3. 512 条 × (URL + 两个 int) ≈ 几十 KB,一秒一次全量覆盖写完全吃得下,
 *    不值得为增量写引入 Room。
 * 无迁移机制,与 RN 侧同一条明文策略:**换结构就换文件名,老数据作废**(v1 在名字里)。
 *
 * 写用「临时文件 + rename」:半截文件下次启动会被 [Json] 拒掉,虽然 catch 得住,
 * 但那等于白丢一次全表。
 */
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
