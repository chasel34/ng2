package com.chasel.ng2n.core.net.web

import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.isFakeError
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.sanitizeNgaJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil

private const val DEFAULT_ROWS_PER_PAGE = 20

private object ArgIndex {

  const val KEY = 0
  const val SUBJECT_ELEMENT = 2
  const val CONTENT_ELEMENT = 3
  const val INFO_ELEMENT = 6
  const val PID = 10

  const val TYPE = 11

  const val AUTHOR_ID = 13
  const val POSTED_AT = 14

  const val SCORES = 15
  const val CONTENT_LENGTH = 16
  const val FROM_CLIENT = 19
}

private typealias PostRecord = LinkedHashMap<String, JsonElement>

private val UserTableJson = Json {
  isLenient = false
  ignoreUnknownKeys = false
  allowSpecialFloatingPointValues = false
}

private fun jsonNumber(value: Double): JsonPrimitive =
  if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
    JsonPrimitive(value.toLong())
  } else {
    JsonPrimitive(value)
  }

private fun jsNumberToString(value: Double): String =
  if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
    value.toLong().toString()
  } else {
    value.toString()
  }

private fun stringOf(argument: JsArgument?): String? = when (argument) {
  is JsArgument.Str -> argument.value
  is JsArgument.Num -> jsNumberToString(argument.value)
  else -> null
}

private val INTEGER_STRING = Regex("""^-?\d+$""")

private fun numberOf(argument: JsArgument?): Double? = when {
  argument is JsArgument.Num -> argument.value
  argument is JsArgument.Str && INTEGER_STRING.matches(argument.value) -> argument.value.toDouble()
  else -> null
}

private val TAGS = Regex("<[^>]*>")

private fun stripTags(html: String): String = TAGS.replace(html, "").jsTrim()

private val TRAILING_SLASHES = Regex("/+$")

private fun attachBaseOf(html: String): String? {
  val raw = readStringVariable(html, "__ATTACH_BASE_VIEW")
  if (raw == null || raw.jsTrim().isEmpty()) return null
  val value = TRAILING_SLASHES.replace(raw.jsTrim(), "")
  return if (value.contains('/')) value else "$value/attachments"
}

private fun parseUserTable(html: String): JsonElement {
  val table = findCall(html, "commonui.userInfo.setAll(")?.args?.getOrNull(0)
  if (table !is JsArgument.Expression) return JsonObject(emptyMap())
  return try {
    when (val parsed = UserTableJson.parseToJsonElement(sanitizeNgaJson(table.text))) {
      is JsonObject, is JsonArray -> parsed
      else -> JsonObject(emptyMap())
    }
  } catch (_: Exception) {
    JsonObject(emptyMap())
  }
}

private fun parseAttachments(html: String): Map<String, JsonObject> {
  val byContentId = LinkedHashMap<String, JsonObject>()
  for (call in findCalls(html, "ubbcode.attach.load(")) {
    val contentId = stringOf(call.args.getOrNull(1))
    val list = call.args.getOrNull(2)
    if (contentId == null || list !is JsArgument.Expression) continue

    val attachs = LinkedHashMap<String, JsonElement>()
    parseObjectLiterals(list.text).forEachIndexed { index, item ->
      val url = item["url"] ?: return@forEachIndexed
      val fields = LinkedHashMap<String, JsonElement>()
      item.forEach { (key, value) -> fields[key] = JsonPrimitive(value) }
      fields["attachurl"] = JsonPrimitive(url)
      attachs[index.toString()] = JsonObject(fields)
    }
    if (attachs.isNotEmpty()) byContentId[contentId] = JsonObject(attachs)
  }
  return byContentId
}

private val ALERT_CONTAINER = Regex("""^alertc(\d+)$""")

private fun parseAlterInfo(html: String): Map<Long, String> {
  val byLou = LinkedHashMap<Long, String>()
  for (call in findCalls(html, "commonui.loadAlertInfo(")) {
    val info = stringOf(call.args.getOrNull(0))
    val lou = ALERT_CONTAINER.find(stringOf(call.args.getOrNull(1)) ?: "")
      ?.groupValues?.get(1)?.toLongOrNull()
    if (info == null || info.jsTrim().isEmpty() || lou == null) continue
    byLou[lou] = info
  }
  return byLou
}

private enum class NestedKind { NOTE, HOT_REPLY }

private fun nestedKindAt(html: String, at: Int): NestedKind {
  val note = html.lastIndexOf("id='comment_for_", at)
  val hot = html.lastIndexOf("id='hightlight_for_", at)
  return if (note > hot) NestedKind.NOTE else NestedKind.HOT_REPLY
}

private class ParsedPosts(
  val rows: LinkedHashMap<String, PostRecord>,
  val starterAuthorId: String?,
)

private fun buildPost(
  html: String,
  args: List<JsArgument>,
  attachments: Map<String, JsonObject>,
): PostRecord? {
  val contentId = elementIdOf(args.getOrNull(ArgIndex.CONTENT_ELEMENT)) ?: return null
  val content = innerHtmlOf(html, contentId) ?: return null

  val subjectId = elementIdOf(args.getOrNull(ArgIndex.SUBJECT_ELEMENT))
  val subject = if (subjectId == null) null else innerHtmlOf(html, subjectId)
  val infoId = elementIdOf(args.getOrNull(ArgIndex.INFO_ELEMENT))
  val postedAtText = if (infoId == null) null else stripTags(innerHtmlOf(html, infoId) ?: "")
  val score = jsNumberOrNaN(stringOf(args.getOrNull(ArgIndex.SCORES))?.split(",")?.getOrNull(1))
  val attachs = attachments[contentId]

  val record = PostRecord()
  record["pid"] = jsonNumber(numberOf(args.getOrNull(ArgIndex.PID)) ?: 0.0)
  record["authorid"] = JsonPrimitive(stringOf(args.getOrNull(ArgIndex.AUTHOR_ID)) ?: "0")
  record["content"] = JsonPrimitive(content)
  record["subject"] = JsonPrimitive(subject ?: "")
  record["postdatetimestamp"] = jsonNumber(numberOf(args.getOrNull(ArgIndex.POSTED_AT)) ?: 0.0)
  record["postdate"] = JsonPrimitive(postedAtText ?: "")
  record["score"] = jsonNumber(if (score.isFinite()) score else 0.0)
  record["type"] = jsonNumber(numberOf(args.getOrNull(ArgIndex.TYPE)) ?: 0.0)
  record["content_length"] = jsonNumber(numberOf(args.getOrNull(ArgIndex.CONTENT_LENGTH)) ?: 0.0)
  record["from_client"] = JsonPrimitive(stringOf(args.getOrNull(ArgIndex.FROM_CLIENT)) ?: "")
  if (attachs != null) record["attachs"] = attachs
  return record
}

private fun jsNumberOrNaN(raw: String?): Double =
  if (raw == null) Double.NaN else com.chasel.ng2n.core.net.jsNumber(raw)

private fun parsePosts(html: String): ParsedPosts {
  val attachments = parseAttachments(html)
  val alterInfo = parseAlterInfo(html)
  val rows = LinkedHashMap<String, PostRecord>()
  var pendingNotes = ArrayList<PostRecord>()
  var pendingHotReplies = ArrayList<PostRecord>()
  var index = 0
  var starterAuthorId: String? = null
  val louByPid = LinkedHashMap<Double, Double>()

  for (call in findCalls(html, "commonui.postArg.proc(")) {
    val key = call.args.getOrNull(ArgIndex.KEY)
    val post = buildPost(html, call.args, attachments) ?: continue

    if (key !is JsArgument.Num) {
      val bucket =
        if (nestedKindAt(html, call.at) == NestedKind.NOTE) pendingNotes else pendingHotReplies
      bucket += post
      continue
    }

    val lou = key.value
    post["lou"] = jsonNumber(lou)
    alterInfo[lou.toLong()]?.let { post["alterinfo"] = JsonPrimitive(it) }
    if (pendingNotes.isNotEmpty()) post["comment"] = indexed(pendingNotes)
    if (pendingHotReplies.isNotEmpty()) post["hotreply"] = indexed(pendingHotReplies)
    rows[index.toString()] = post

    louByPid[postPid(post)] = lou
    if (lou == 0.0) starterAuthorId = (post["authorid"] as? JsonPrimitive)?.content
    pendingNotes = ArrayList()
    pendingHotReplies = ArrayList()
    index += 1
  }

  for (row in rows.values) {
    val hot = row["hotreply"] as? JsonObject ?: continue
    val patched = LinkedHashMap<String, JsonElement>()
    for ((key, reply) in hot) {
      val record = reply as? JsonObject
      if (record == null) {
        patched[key] = reply
        continue
      }
      val lou = louByPid[(record["pid"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Double.NaN]
      patched[key] = if (lou == null) record else JsonObject(LinkedHashMap(record).also { it["lou"] = jsonNumber(lou) })
    }
    row["hotreply"] = JsonObject(patched)
  }

  return ParsedPosts(rows, starterAuthorId)
}

private fun indexed(records: List<PostRecord>): JsonObject {
  val out = LinkedHashMap<String, JsonElement>()
  records.forEachIndexed { index, record -> out[index.toString()] = JsonObject(record) }
  return JsonObject(out)
}

private fun postPid(record: PostRecord): Double =
  (record["pid"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Double.NaN

private class Pagination(val page: Long, val rowsPerPage: Long, val totalRows: Long)

private val PAGE_VAR = Regex("""var\s+__PAGE\s*=\s*\{([^}]*)\}""")

private fun parsePagination(html: String): Pagination {
  val pageVar = PAGE_VAR.find(html)?.groupValues?.get(1)
  fun field(key: String): Long? {
    if (pageVar == null) return null
    return Regex("""(?:^|,)\s*$key\s*:\s*(\d+)""").find(pageVar)?.groupValues?.get(1)?.toLongOrNull()
  }

  val rowsPerPage = field("3") ?: DEFAULT_ROWS_PER_PAGE.toLong()
  val page = readIntVariable(html, "__CURRENT_PAGE") ?: field("2") ?: 1L
  val totalPages = field("1")

  val replies = setDefaultReplies(html)
  if (replies != null) {
    val rows = replies + 1
    val pages = maxOf(1.0, ceil(rows.toDouble() / rowsPerPage.toDouble()))
    if (totalPages == null || pages == totalPages.toDouble()) {
      return Pagination(page, rowsPerPage, rows)
    }
  }
  return Pagination(page, rowsPerPage, (totalPages ?: 1L) * rowsPerPage)
}

private fun setDefaultReplies(html: String): Long? {
  val call = findCalls(html, "commonui.postArg.setDefault(").firstOrNull() ?: return null
  val value = numberOf(call.args.getOrNull(call.args.size - 3)) ?: return null
  return if (value.isFinite()) value.toLong() else null
}

private fun setDefaultStarterId(html: String): Double? {
  val call = findCalls(html, "commonui.postArg.setDefault(").firstOrNull() ?: return null
  return numberOf(call.args.getOrNull(3))
}

private class ServerMessage(val code: String, val info: String)

private fun readMessage(html: String): ServerMessage? {
  val code = readMarkedSection(html, "msgcode")?.jsTrim() ?: return null
  return ServerMessage(code, readMarkedSection(html, "msginfo")?.jsTrim() ?: "")
}

fun parseReadPageHtml(html: String, via: String? = null): NgaEnvelope {
  val message = readMessage(html)
  if (message != null) {
    val text = if (message.info.isEmpty()) message.code else "${message.code}:${message.info}"
    if (!isFakeError(message.info)) {
      throw NgaError(NgaErrorKind.SERVER, text, code = JsonPrimitive(message.code), via = via)
    }
  }

  val parsed = parsePosts(html)
  if (parsed.rows.isEmpty()) {
    throw NgaError(NgaErrorKind.PARSE, "Web 反解没有找到任何楼层", via = via)
  }

  val users = parseUserTable(html)
  val pagination = parsePagination(html)
  val starterId = setDefaultStarterId(html)
  val starterKey =
    if (starterId != null && starterId > 0) jsNumberToString(starterId) else (parsed.starterAuthorId ?: "")
  val starterName = ((users as? JsonObject)?.get(starterKey) as? JsonObject)
    ?.get("username")
    ?.let { if (it is JsonPrimitive && it.isString) it.content else null }

  val attachBase = attachBaseOf(html)
  val data = buildJsonObject {
    if (attachBase != null) {
      put("__GLOBAL", buildJsonObject { put("_ATTACH_BASE_VIEW", JsonPrimitive(attachBase)) })
    }
    put("__U", users)
    put(
      "__T",
      buildJsonObject {
        put("tid", JsonPrimitive(readIntVariable(html, "__CURRENT_TID") ?: 0L))
        put("subject", JsonPrimitive(innerHtmlOf(html, "currentTopicName")?.jsTrim() ?: ""))
        if (starterId != null) put("authorid", jsonNumber(starterId))
        if (starterName != null) put("author", JsonPrimitive(starterName))
      },
    )
    put(
      "__F",
      buildJsonObject {
        put("name", JsonPrimitive(innerHtmlOf(html, "currentForumName")?.jsTrim() ?: ""))
      },
    )
    put(
      "__R",
      JsonObject(parsed.rows.mapValues { (_, record) -> JsonObject(record) as JsonElement }),
    )
    put("__PAGE", JsonPrimitive(pagination.page))
    put("__ROWS", JsonPrimitive(pagination.totalRows))
    put("__R__ROWS_PAGE", JsonPrimitive(pagination.rowsPerPage))
  }

  return NgaEnvelope(root = buildJsonObject { put("data", data) }, data = data)
}
