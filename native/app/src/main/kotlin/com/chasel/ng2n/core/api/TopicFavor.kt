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

/**
 * 主题收藏夹(CONTEXT.md「收藏夹」——云端多夹,与版块收藏无关)。
 * 直译 `src/core/api/topic-favor.ts`。
 *
 * 走 `topic_favor_v2` 接口族(API 文档 §5.1),全部 JSON:
 *
 * - 收藏夹列表 `list_folder`:`data["0"].*` 是 `{id, name, length, default?}`,
 *   **有 `default` 键的那个是默认夹**(实测同时带 `type: 2`,以 `default` 为准)
 * - 加入 `add`(form `tid`)/ 移出 `del`(form **`tidarray`**,不是 `tid`!)——
 *   按夹删,取消一个夹不影响主题在其他夹里的归属(实测 2026-08-08)
 * - 新建 `new_folder`:新夹 id 在 `data["1"]` 或 `data["0"]`
 * - 重命名/设默认 `modify_folder`、删除 `del_folder`
 * - 某夹的主题列表 = `thread.php?favor=<夹id>`,响应形状与主题列表相同,复用其解析
 *
 * 写操作不在这里判「操作成功」文案:服务端出错时 envelope 已经抛 `kind = SERVER`,
 * 能走到返回就是成功。操作后的列表一律以重拉的服务端数据为准。
 */

/** 一个云端收藏夹。 */
@Serializable
data class FavoriteFolder(
  val id: Long,
  val name: String,
  /** 夹内主题数(服务端字段名 `length`) */
  val count: Long = 0,
  /** 收藏时不指定夹就落进默认夹;全站至多一个 */
  val isDefault: Boolean = false,
)

/** `topic_favor_v2` 的公共 `__lib`。 */
private const val LIB = "topic_favor_v2"

/** 设默认传 2、不动默认位传 0(API 文档 §5.1 的 `opt`)。 */
private const val OPT_DEFAULT = 2
private const val OPT_KEEP = 0

/** 解收藏夹列表(传响应的 `data`)。坏条目跳过,整体不炸(core/api 纪律)。 */
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

/** 拉收藏夹列表。空 `data["0"]`(一个夹都没有)是合法状态,返回空列表。 */
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

/**
 * 某收藏夹的主题列表(`thread.php?favor=<夹id>`,API 文档 §2)。
 * 响应形状与版块主题列表一致(`__F` 是空对象),直接复用 [parseTopicList]。
 */
suspend fun fetchFavoriteTopics(client: NgaClient, folderId: Long, page: Int): TopicList {
  val result = client.execute(
    NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      query = queryOf("favor" to folderId, "page" to page),
      // 和版块列表共用同一条 comboCache 记录,形状校验也必须是同一份
      validate = ::rejectNonTopicList,
    ),
  )
  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "收藏主题列表响应里没有 data", via = result.via)
  }
  return parseTopicList(data)
}

/** 把主题加进一个收藏夹。 */
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

/** 把主题从一个收藏夹移出。⚠️ 参数名是 `tidarray`(API 文档 §5.1)。 */
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

/**
 * 新建收藏夹,返回新夹 id;响应里挖不出 id 时返回 null——
 * 创建本身已成功(失败早抛了),调用方反正要重拉列表。
 */
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
  // 新夹 id 在 data["1"] 或 data["0"](MNGA 调研报告 §E3);data["0"] 常是「操作成功」文案
  val data = result.data as? JsonObject ?: JsonObject(emptyMap())
  return int(data, "1") ?: int(data, "0")
}

/**
 * 重命名 / 设默认(同一个 `modify_folder`)。
 * [name] 必传:设默认时传夹的现名;[asDefault] 传 true 把这个夹设为默认。
 */
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

/** 删除收藏夹(夹里的收藏一并没了,UI 侧要把话说清楚)。 */
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
