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

  val fid = nonZero(signedBoardId(int(raw, "fid")))
  val stid = nonZero(int(raw, "stid"))
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

fun parseBoardTree(root: JsonElement?): BoardTree {
  if (root !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "分类树响应顶层不是对象")
  }
  val other = root["other"]
  val table = parseIconTable(other)

  val categories = ArrayList<BoardCategory>()
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

fun pickActiveAnnouncement(
  announcements: List<HomeAnnouncement>,
  nowMs: Long,
): HomeAnnouncement? {
  val seconds = Math.floorDiv(nowMs, 1000L)
  return announcements.firstOrNull { item ->
    (item.startAt == null || seconds >= item.startAt) && (item.endAt == null || seconds <= item.endAt)
  }
}

suspend fun fetchBoardTree(client: NgaClient): BoardTree {
  val result = client.execute(
    NgaRequest(
      path = "app_api.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "home", "__act" to "category"),
      shape = EnvelopeShape.BARE,
      validate = ::rejectNonBoardTree,
    ),
  )
  return parseBoardTree(result.root)
}
