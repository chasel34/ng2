package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class FavoriteFolder(
  val id: Long,
  val name: String,
  val count: Long = 0,
  val isDefault: Boolean = false,
)

private const val LIB = "topic_favor_v2"

private const val OPT_DEFAULT = 2
private const val OPT_KEEP = 0

fun parseFavoriteFolders(data: JsonElement?): List<FavoriteFolder> {
  val root = data as? JsonObject ?: JsonObject(emptyMap())
  return orderedValues(root["0"]).mapNotNull { raw ->
    if (raw !is JsonObject) return@mapNotNull null
    val id = int(raw, "id") ?: return@mapNotNull null
    val name = str(raw, "name") ?: return@mapNotNull null
    FavoriteFolder(
      id = id,
      name = name,
      count = int(raw, "length") ?: 0,
      isDefault = raw.containsKey("default"),
    )
  }
}

suspend fun fetchFavoriteFolders(client: NgaClient): List<FavoriteFolder> {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to LIB, "__act" to "list_folder", "page" to 1),
    ),
  )
  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "收藏夹列表响应里没有 data", via = result.via)
  }
  return parseFavoriteFolders(data)
}

suspend fun fetchFavoriteTopics(client: NgaClient, folderId: Long, page: Int): TopicList {
  val result = client.execute(
    NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      query = queryOf("favor" to folderId, "page" to page),
      validate = ::rejectNonTopicList,
    ),
  )
  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "收藏主题列表响应里没有 data", via = result.via)
  }
  return parseTopicList(data)
}

suspend fun addTopicFavorite(client: NgaClient, tid: Long, folderId: Long) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to LIB, "__act" to "add"),
      form = queryOf("tid" to tid, "folder" to folderId),
    ),
  )
}

suspend fun removeTopicFavorite(client: NgaClient, tid: Long, folderId: Long) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to LIB, "__act" to "del"),
      form = queryOf("tidarray" to tid, "folder" to folderId),
    ),
  )
}

suspend fun createFavoriteFolder(
  client: NgaClient,
  name: String,
  asDefault: Boolean = false,
): Long? {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to LIB, "__act" to "new_folder", "raw" to 3),
      form = queryOf("name" to name, "opt" to if (asDefault) OPT_DEFAULT else OPT_KEEP),
    ),
  )
  val data = result.data as? JsonObject ?: JsonObject(emptyMap())
  return int(data, "1") ?: int(data, "0")
}

suspend fun modifyFavoriteFolder(
  client: NgaClient,
  folderId: Long,
  name: String,
  asDefault: Boolean = false,
) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to LIB, "__act" to "modify_folder", "raw" to 3),
      form = queryOf(
        "folder" to folderId,
        "name" to name,
        "opt" to if (asDefault) OPT_DEFAULT else OPT_KEEP,
      ),
    ),
  )
}

suspend fun deleteFavoriteFolder(client: NgaClient, folderId: Long) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to LIB, "__act" to "del_folder", "raw" to 3),
      form = queryOf("folder" to folderId),
    ),
  )
}
