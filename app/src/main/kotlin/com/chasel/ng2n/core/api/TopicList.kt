package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.decodeTitleStyle
import com.chasel.ng2n.core.local.isAnonymousAuthor
import com.chasel.ng2n.core.local.parseTopicMisc
import com.chasel.ng2n.core.local.resolveAuthorName
import com.chasel.ng2n.core.local.signedBoardId
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.jsOwnEntries
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.max

private const val TYPE_LOCKED = 1024L
private const val TYPE_ATTACHMENT = 8192L
private const val TYPE_COLLECTION = 0x8000L
private const val TYPE_BOARD_MIRROR = 0x200000L

const val DEFAULT_TOPIC_ROWS_PER_PAGE = 35

private const val UNTITLED = "无标题"

private val FAV_PATTERN = Regex("[?&]fav=([0-9a-fA-F]+)")
private val TPCURL_TID_PATTERN = Regex("[?&]tid=(\\d+)")

private fun reparse(text: String): JsonElement? =
  runCatching { NgaJson.parseToJsonElement(text) }.getOrNull()

internal fun unknownOf(value: JsonElement?): Any? = when {
  value == null || value is JsonNull -> null
  value is JsonPrimitive ->
    if (value.isString) value.content else value.content.toDoubleOrNull() ?: value.content
  else -> value
}

private fun parseParent(raw: JsonElement?): TopicParent? {
  var value = raw
  if (value is JsonPrimitive && value.isString) {
    value = reparse(value.content) ?: return null
  }
  if (value !is JsonObject) return null

  val name = str(value, "2") ?: return null
  val fid = nonZero(signedBoardId(int(value, "0")))
  val stid = nonZero(int(value, "1"))

  return TopicParent(fid = fid, stid = stid, name = name)
}

private fun parseShortcut(
  type: Long,
  tid: Long,
  fid: Long?,
  stid: Long?,
  sfid: Long?,
): TopicShortcut? {
  if (type and TYPE_COLLECTION != 0L) {
    return TopicShortcut(kind = BoardKind.COLLECTION, id = stid ?: tid)
  }
  if (type and TYPE_BOARD_MIRROR != 0L) {
    val id = sfid ?: fid ?: return null
    return TopicShortcut(kind = BoardKind.BOARD, id = id)
  }
  return null
}

private fun parseTopicReply(raw: JsonElement?): TopicReply? {
  if (raw !is JsonObject) return null
  val pid = nonZero(int(raw, "pid")) ?: return null
  val content = raw["content"]
  return TopicReply(
    pid = pid,
    content = if (content is JsonPrimitive && content.isString) content.content else "",
    postedAt = int(raw, "postdatetimestamp") ?: int(raw, "postdate") ?: 0,
  )
}

private fun parseTopic(raw: JsonElement?): Topic? {
  if (raw !is JsonObject) return null

  val tpcurl = str(raw, "tpcurl")
  val tid = nonZero(int(raw, "quote_from"))
    ?: nonZero(int(raw, "tid"))
    ?: tpcurl?.let { TPCURL_TID_PATTERN.find(it)?.groupValues?.get(1)?.toLongOrNull() }
    ?: return null

  val misc = parseTopicMisc(unknownOf(raw["topic_misc"]))
  val miscVar = raw["topic_misc_var"] as? JsonObject
  val stid = misc.stid ?: miscVar?.let { nonZero(int(it, "2")) }
  val sfid = misc.sfid ?: miscVar?.let { nonZero(signedBoardId(int(it, "3"))) }

  val type = int(raw, "type") ?: 0
  val fid = nonZero(signedBoardId(int(raw, "fid")))
  val rawAuthor = str(raw, "author") ?: ""
  val favCode = tpcurl?.let { FAV_PATTERN.find(it)?.groupValues?.get(1) }
  val lastPoster = str(raw, "lastposter")

  return Topic(
    tid = tid,
    fid = fid,
    subject = text(raw, "subject") ?: UNTITLED,
    titleStyle = decodeTitleStyle(
      titlefont = unknownOf(raw["titlefont"]),
      topicMisc = unknownOf(raw["topic_misc"]),
    ),
    author = resolveAuthorName(rawAuthor),
    authorId = nonZero(int(raw, "authorid")),
    anonymous = isAnonymousAuthor(rawAuthor),
    lastPoster = lastPoster?.let(::resolveAuthorName),
    replies = int(raw, "replies") ?: 0,
    postedAt = int(raw, "postdate") ?: 0,
    lastPostAt = int(raw, "lastpost") ?: 0,
    favCode = favCode,
    locked = type and TYPE_LOCKED != 0L,
    hasAttachment = type and TYPE_ATTACHMENT != 0L,
    isCollection = type and TYPE_COLLECTION != 0L,
    isBoardMirror = type and TYPE_BOARD_MIRROR != 0L,
    shortcut = parseShortcut(type, tid, fid, stid, sfid),
    parent = parseParent(raw["parent"]),
    jumpUrl = str(raw, "jumpurl"),
    reply = parseTopicReply(raw["__P"]),
    denied = str(raw, "denied") == "1",
  )
}

private fun boardIdentity(name: String, fid: Long?, stid: Long?): Board? {
  val id = stid ?: fid ?: return null
  return Board(
    id = id,
    kind = if (stid == null) BoardKind.BOARD else BoardKind.COLLECTION,
    fid = fid,
    stid = stid,
    name = name,
  )
}

private fun parseSubBoard(key: String, raw: JsonElement?): SubBoard? {
  if (raw !is JsonObject) return null
  val name = str(raw, "1") ?: return null
  val collection = key.startsWith("t")
  val id = nonZero(if (collection) int(raw, "0") else signedBoardId(int(raw, "0"))) ?: return null

  val board = boardIdentity(
    name = name,
    fid = if (collection) null else id,
    stid = if (collection) id else null,
  ) ?: return null
  val filterId = nonZero(int(raw, "3"))

  return SubBoard(
    id = board.id,
    kind = board.kind,
    fid = board.fid,
    stid = board.stid,
    name = board.name,
    info = str(raw, "2"),
    filterId = filterId ?: id,
    filterType = if (filterId == null) 0 else 1,
    attributes = int(raw, "4") ?: 0,
  )
}

private fun parseBoard(raw: JsonElement?): Board? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null
  val board = boardIdentity(
    name = name,
    fid = nonZero(signedBoardId(int(raw, "fid"))),
    stid = nonZero(int(raw, "stid")),
  ) ?: return null
  return board.copy(head = nonZero(int(raw, "topped_topic")))
}

private val TOPIC_LIST_STRUCTURE_KEYS = listOf("__T", "__F", "__ROWS")

fun hasTopicListStructure(data: JsonElement?): Boolean =
  data is JsonObject && TOPIC_LIST_STRUCTURE_KEYS.any { it in data }

private const val NOT_A_TOPIC_LIST = "响应里没有主题列表结构（多半是被限流或拦截了）"

fun rejectNonTopicList(envelope: NgaEnvelope): String? {
  if (envelope.fakeError != null) return null
  val data = envelope.data
  if (data !is JsonObject) return "响应里没有 data"
  return if (hasTopicListStructure(data)) null else NOT_A_TOPIC_LIST
}

fun serverEmptyTopicList(): TopicList = parseTopicList(null).copy(listStructure = true)

fun parseTopicList(data: JsonElement?): TopicList {
  val root = data as? JsonObject ?: JsonObject(emptyMap())

  val topics = orderedValues(root["__T"]).mapNotNull(::parseTopic)

  val forum = root["__F"] as? JsonObject
  val board = parseBoard(forum)
  val subForums = (forum?.get("sub_forums") as? JsonObject)?.let(::jsOwnEntries).orEmpty()
  val subBoards = subForums.mapNotNull { (key, raw) -> parseSubBoard(key, raw) }

  val rowsPerPage = (nonZero(int(root, "__T__ROWS_PAGE")) ?: DEFAULT_TOPIC_ROWS_PER_PAGE.toLong())
  val totalRows = nonZero(int(root, "__ROWS"))
    ?: nonZero(int(root, "__T__ROWS"))
    ?: topics.size.toLong()

  return TopicList(
    topics = topics,
    board = board,
    subBoards = subBoards,
    totalRows = totalRows,
    rowsPerPage = rowsPerPage.toInt(),
    totalPages = pageCount(totalRows, rowsPerPage),
    listStructure = hasTopicListStructure(data),
  )
}

internal fun pageCount(totalRows: Long, rowsPerPage: Long): Int {
  if (rowsPerPage <= 0) return 1
  return max(1.0, ceil(totalRows.toDouble() / rowsPerPage.toDouble())).toInt()
}

fun mergeTopicPages(pages: List<TopicList>): List<Topic> {
  val seen = HashSet<Long>()
  val merged = ArrayList<Topic>()
  for (page in pages) {
    for (topic in page.topics) {
      if (!seen.add(topic.tid)) continue
      merged += topic
    }
  }
  return merged
}

enum class TopicSort { LAST_POST, POST_DATE }

suspend fun fetchTopicList(
  client: NgaClient,
  boardId: Long,
  kind: BoardKind,
  page: Int,
  sort: TopicSort = TopicSort.LAST_POST,
  recommend: Boolean = false,
): TopicList {
  val result = client.execute(
    NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      query = queryOf(
        (if (kind == BoardKind.COLLECTION) "stid" else "fid") to boardId,
        "page" to page,
        "recommend" to if (recommend) 1 else null,
        "order_by" to if (recommend || sort == TopicSort.POST_DATE) "postdatedesc" else null,
        "user" to if (recommend) 1 else null,
      ),
      validate = ::rejectNonTopicList,
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "主题列表响应里没有 data", via = result.via)
  }
  if (!hasTopicListStructure(data)) {
    throw NgaError(NgaErrorKind.PARSE, NOT_A_TOPIC_LIST, via = result.via)
  }
  return parseTopicList(data)
}
