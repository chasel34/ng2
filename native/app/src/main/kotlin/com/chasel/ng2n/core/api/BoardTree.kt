package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.signedBoardId
import com.chasel.ng2n.core.net.EnvelopeShape
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.jsNumber
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.jsTrunc
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 版块分类树的解析(API 文档 §1.1)。直译 `src/core/api/board-tree.ts`。
 *
 * `app_api.php?__lib=home&__act=category` 的响应长这样(真实抓包,见金样本 `api/board-tree`):
 *
 * ```jsonc
 * {
 *   "data":  { "0": { "_id": "wow", "name": "魔兽世界",
 *                     "groups": { "0": { "name": "…", "id": 10004,
 *                                        "forums": { "0": { "fid": 7, "stid": 123, "name": "…", "info": "…" } } } } } },
 *   "other": { "forum_icon_list": { … }, "appcolumn_notis": { … }, "forum_recommend": { … } }
 * }
 * ```
 *
 * 三层全部是**字符串数字键当数组**(API 文档 §0.6),自动 JSON 映射失效,只能手工遍历;
 * 每一层的字段都可能缺、可能是别的类型,所以坏条目一律跳过而不是让整棵树炸掉。
 */

/**
 * 版块图标地址的拼法,全部来自 `other.forum_icon_list`:
 * 前缀 + id + 后缀(普通版块用 `f_px_l`/`f_sx_l`,合集用 `s_px_l`/`s_sx_l`)。
 *
 * `f` / `s` 两个字符串是**登记过图标的 id 清单**,形如 `" 639,?487,-349066,?268,…"`
 * (id 与 `?` 打头的缓存版本号交替)。清单外的 id 请求图标必 404 ——
 * 2026-08-07 抽样 14 个版块逐个实测,命中与否和 HTTP 200/404 完全一致——
 * 所以不在清单里就不给地址,免得一屏 60 个格子打一片 404。
 */
private class IconFamily(val prefix: String, val suffix: String, val ids: Set<Long>)

private class IconTable(val board: IconFamily?, val collection: IconFamily?) {
  companion object {
    val EMPTY = IconTable(null, null)
  }
}

private fun parseIconIds(value: JsonElement?): Set<Long> {
  if (value !is JsonPrimitive || !value.isString) return emptySet()
  val ids = HashSet<Long>()
  for (token in value.content.split(",")) {
    val trimmed = token.jsTrim()
    if (trimmed.isEmpty() || trimmed.startsWith("?")) continue
    val id = jsNumber(trimmed)
    if (id.isFinite()) ids.add(jsTrunc(id))
  }
  return ids
}

private fun parseIconTable(other: JsonElement?): IconTable {
  if (other !is JsonObject) return IconTable.EMPTY
  val list = other["forum_icon_list"] as? JsonObject ?: return IconTable.EMPTY

  fun family(prefixKey: String, suffixKey: String, idsKey: String): IconFamily? {
    val prefix = str(list, prefixKey) ?: return null
    // 后缀字段偶尔是逗号分隔的多档(c_sx_l 就是),取第一档
    val suffix = str(list, suffixKey)?.split(",")?.firstOrNull() ?: return null
    return IconFamily(prefix, suffix, parseIconIds(list[idsKey]))
  }

  return IconTable(
    board = family("f_px_l", "f_sx_l", "f"),
    collection = family("s_px_l", "s_sx_l", "s"),
  )
}

private fun iconUrl(table: IconTable, id: Long, kind: BoardKind): String? {
  val family = if (kind == BoardKind.COLLECTION) table.collection else table.board
  if (family == null || id !in family.ids) return null
  return "${family.prefix}$id${family.suffix}"
}

private fun parseBoard(raw: JsonElement?, table: IconTable): Board? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null

  // 0 不是有效 id:普通版块常见下发 stid:0 表示「不是合集」,
  // 当成真 stid 会把整个版块错判成合集,thread.php 也会拿 stid=0 去查。
  // fid 可以是负数(-7 网事杂谈、个人版面),过一道符号还原;stid 是主题 id,不适用
  val fid = nonZero(signedBoardId(int(raw, "fid")))
  val stid = nonZero(int(raw, "stid"))
  // stid 优先于 fid(CONTEXT.md「合集」):合集与普通版块互斥,下游一律只认这一个 id
  val id = stid ?: fid ?: return null
  val kind = if (stid == null) BoardKind.BOARD else BoardKind.COLLECTION

  return Board(
    id = id,
    kind = kind,
    fid = fid,
    stid = stid,
    name = name,
    info = str(raw, "info"),
    iconUrl = iconUrl(table, id, kind),
  )
}

private fun parseGroup(raw: JsonElement?, fallbackId: String, table: IconTable): BoardGroup? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null
  val boards = orderedValues(raw["forums"]).mapNotNull { parseBoard(it, table) }
  if (boards.isEmpty()) return null
  val id = int(raw, "id")
  return BoardGroup(id = id?.toString() ?: fallbackId, name = name, boards = boards)
}

private fun parseCategory(raw: JsonElement?, fallbackId: String, table: IconTable): BoardCategory? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null
  val id = str(raw, "_id") ?: fallbackId
  val groups = orderedValues(raw["groups"])
    .mapIndexedNotNull { index, group -> parseGroup(group, "$id-group-$index", table) }
  if (groups.isEmpty()) return null
  return BoardCategory(id = id, name = name, groups = groups)
}

private fun parseAnnouncements(other: JsonElement?): List<HomeAnnouncement> {
  if (other !is JsonObject) return emptyList()
  val block = other["appcolumn_notis"] as? JsonObject ?: return emptyList()
  val version = int(block, "version") ?: 0

  return orderedEntries(block["notis"]).mapNotNull { (key, raw) ->
    if (raw !is JsonObject) return@mapNotNull null
    val title = str(raw, "title") ?: return@mapNotNull null
    HomeAnnouncement(
      id = "$version-$key",
      title = title,
      url = str(raw, "url"),
      startAt = int(raw, "start_at"),
      endAt = int(raw, "end_at"),
    )
  }
}

/**
 * 解析整棵分类树。传的是信封的 `root`——图标清单与公告都挂在顶层 `other` 上,不在 `data` 里。
 *
 * 一个版块都没解析出来时抛 `kind = PARSE`:对调用方来说这和「被封」是一回事,
 * 该继续用上一次的本地缓存,而不是把首页刷成空。
 */
fun parseBoardTree(root: JsonElement?): BoardTree {
  if (root !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "分类树响应顶层不是对象")
  }
  val other = root["other"]
  val table = parseIconTable(other)

  val categories = ArrayList<BoardCategory>()
  // 「推荐版块」是服务端单独下发的一档,排在所有分类前面(对应设计稿第一个 tab)
  val recommend = (other as? JsonObject)
    ?.let { parseCategory(it["forum_recommend"], "recommend", table) }
  if (recommend != null) categories += recommend

  orderedValues(root["data"]).forEachIndexed { index, raw ->
    val category = parseCategory(raw, "category-$index", table)
    if (category != null) categories += category
  }

  if (categories.isEmpty()) {
    throw NgaError(NgaErrorKind.PARSE, "分类树里一个版块都没解析出来")
  }

  return BoardTree(categories = categories, announcements = parseAnnouncements(other))
}

/**
 * 挑一条当前该展示的公告:`startAt`/`endAt` 划出展示窗口,缺省表示不限。
 * [nowMs] 是**毫秒**时间戳,服务端字段是秒。
 */
fun pickActiveAnnouncement(
  announcements: List<HomeAnnouncement>,
  nowMs: Long,
): HomeAnnouncement? {
  val seconds = Math.floorDiv(nowMs, 1000L)
  return announcements.firstOrNull { item ->
    (item.startAt == null || seconds >= item.startAt) && (item.endAt == null || seconds <= item.endAt)
  }
}

/**
 * 拉线上分类树(`POST app_api.php?__lib=home&__act=category`,API 文档 §1.1)。
 *
 * 不需要登录:游客也能拿到完整的树,所以首页在没有账号时照样铺得满。
 */
suspend fun fetchBoardTree(client: NgaClient): BoardTree {
  val result = client.execute(
    NgaRequest(
      path = "app_api.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "home", "__act" to "category"),
      // 这个接口的数据横跨顶层的 `data` 与 `other`(图标清单、公告、推荐版块都在 other 里),
      // 解析拿的是 `root` 而不是 `data`——所以它不该受「顶层必须有 data 壳」那条约束。
      // 实测响应确实带 `data` 键,声明 BARE 只是让「我们读的是顶层」这件事写在明面上
      shape = EnvelopeShape.BARE,
    ),
  )
  return parseBoardTree(result.root)
}
