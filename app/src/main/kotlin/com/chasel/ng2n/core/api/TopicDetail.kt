package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.REPUTATION_SCALE
import com.chasel.ng2n.core.local.resolveAuthorName
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.jsOwnEntries
import com.chasel.ng2n.core.net.queryOf
import com.chasel.ng2n.core.net.strategies.TOPIC_CACHE_STRATEGY_NAME
import com.chasel.ng2n.core.net.strategies.WEB_FALLBACK_STRATEGY_NAME
import com.chasel.ng2n.core.net.strategies.serializeEnvelope
import com.chasel.ng2n.core.net.strategies.topicCacheKeyOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_FLOOR_ROWS_PER_PAGE = 20L

private val MUTE_BUFF_IDS = listOf("105", "117")

private const val NUKED_YZ = -1L

private const val UNTITLED = "无标题"

private val AVATAR_URL_PATTERN = Regex("https?://[^\"',\\s\\\\]+")
private val HTTP_PREFIX = Regex("^https?://")

fun parseAvatarUrl(raw: JsonElement?): String? {
  if (raw !is JsonPrimitive || !raw.isString) return null
  val value = raw.content.replace("\\/", "/").trim()
  if (value.isEmpty()) return null
  if (HTTP_PREFIX.containsMatchIn(value)) return value
  return AVATAR_URL_PATTERN.find(value)?.value
}

private fun parseClient(raw: JsonElement?): FloorClient {
  val value = if (raw is JsonPrimitive && raw.isString) raw.content.lowercase() else ""
  if (value.contains("android")) return FloorClient.ANDROID
  if (value.contains("ios") || value.contains("iphone")) return FloorClient.IOS
  return FloorClient.OTHER
}

private fun parseMuted(raw: JsonElement?): Boolean {
  if (raw !is JsonObject) return false
  return MUTE_BUFF_IDS.any { raw[it] != null }
}

private fun userKey(rawKey: String, context: String): String =
  if (rawKey.startsWith("-")) "$context,$rawKey" else rawKey

private fun parseUser(
  rawKey: String,
  raw: JsonElement?,
  context: String,
  groups: Map<String, String>,
): FloorUser? {
  if (raw !is JsonObject) return null

  val rawName = str(raw, "username") ?: ""
  val displayName = resolveAuthorName(rawName)
  val anonymous = rawKey.startsWith("-")
  val uid = if (anonymous) null else int(raw, "uid")
  val memberId = int(raw, "memberid")

  return FloorUser(
    key = userKey(rawKey, context),
    uid = uid,
    name = displayName.ifEmpty { "匿名" },
    rawName = rawName,
    anonymous = anonymous,
    avatarUrl = parseAvatarUrl(raw["avatar"]),
    level = memberId?.let { groups[it.toString()] },
    signature = str(raw, "signature") ?: str(raw, "sign"),
    reputation = (int(raw, "rvrc") ?: int(raw, "fame") ?: 0L) / REPUTATION_SCALE,
    postCount = int(raw, "postnum") ?: int(raw, "posts") ?: 0L,
    muted = parseMuted(raw["buffs"]),
    nuked = int(raw, "yz") == NUKED_YZ,
  )
}

private fun parseGroups(raw: JsonElement?): Map<String, String> {
  if (raw !is JsonObject) return emptyMap()
  val groups = LinkedHashMap<String, String>()
  for ((key, value) in jsOwnEntries(raw)) {
    if (value !is JsonObject) continue
    val name = str(value, "0") ?: continue
    groups[key] = name
  }
  return groups
}

private fun parseAttachment(raw: JsonElement?, base: String): FloorAttachment? {
  if (raw !is JsonObject) return null
  val attachUrl = str(raw, "attachurl") ?: return null

  val url = "$base/${attachUrl.trimStart('/')}"
  val thumb = int(raw, "thumb")
  val hasThumbnail = thumb != null && thumb != 0L

  return FloorAttachment(
    url = url,
    thumbnailUrl = if (hasThumbnail) "$url$THUMBNAIL_SUFFIX" else null,
    kind = str(raw, "type") ?: "file",
    name = str(raw, "name"),
    sizeKb = int(raw, "size"),
  )
}

private class FloorContext(
  val context: String,
  val attachBase: String,
  val starterUid: Long?,
  val starterRawName: String?,
  val users: Map<String, FloorUser>,
)

private fun isStarterFloor(user: FloorUser?, ctx: FloorContext): Boolean {
  if (user == null) return false
  if (user.anonymous) {
    return ctx.starterRawName != null && user.rawName == ctx.starterRawName
  }
  return user.uid != null && user.uid == ctx.starterUid
}

private fun parseFloor(raw: JsonElement?, ctx: FloorContext, depth: Int = 0): Floor? {
  if (raw !is JsonObject) return null
  val content = raw["content"]
  if (content !is JsonPrimitive || !content.isString) return null

  val authorId = int(raw, "authorid") ?: 0
  val authorKey = userKey(authorId.toString(), ctx.context)
  val attachs = raw["attachs"] as? JsonObject
  val attachments = attachs
    ?.let { orderedValues(it).mapNotNull { item -> parseAttachment(item, ctx.attachBase) } }
    ?: emptyList()
  val notes = if (depth == 0) {
    orderedValues(raw["comment"]).mapNotNull { parseFloor(it, ctx, depth + 1) }
  } else {
    emptyList()
  }

  return Floor(
    pid = int(raw, "pid") ?: 0,
    lou = int(raw, "lou") ?: 0,
    authorId = authorId,
    authorKey = authorKey,
    isStarter = isStarterFloor(ctx.users[authorKey], ctx),
    content = content.content,
    subject = text(raw, "subject"),
    postedAt = int(raw, "postdatetimestamp") ?: 0,
    postedAtText = str(raw, "postdate") ?: "",
    score = int(raw, "score") ?: 0,
    edited = str(raw, "alterinfo") != null,
    client = parseClient(raw["from_client"]),
    attachments = attachments,
    notes = notes,
    vote = str(raw, "vote"),
  )
}

fun parseTopicDetail(
  data: JsonElement?,
  context: String,
  source: TopicSource = TopicSource.NATIVE,
): TopicDetail {
  val root = data as? JsonObject ?: JsonObject(emptyMap())

  val attachBase = normalizeAttachBase(
    unknownOf((root["__GLOBAL"] as? JsonObject)?.get("_ATTACH_BASE_VIEW")),
  )

  val userTable = root["__U"] as? JsonObject ?: JsonObject(emptyMap())
  val groups = parseGroups(userTable["__GROUPS"] ?: root["__GROUPS"])
  val users = LinkedHashMap<String, FloorUser>()
  for ((key, raw) in jsOwnEntries(userTable)) {
    if (key.startsWith("__")) continue
    val user = parseUser(key, raw, context, groups) ?: continue
    users[user.key] = user
  }

  val topic = root["__T"] as? JsonObject ?: JsonObject(emptyMap())
  val starterUid = int(topic, "authorid")
  val ctx = FloorContext(
    context = context,
    attachBase = attachBase,
    starterUid = if (starterUid == null || starterUid < 0) null else starterUid,
    starterRawName = str(topic, "author"),
    users = users,
  )

  val rows = orderedValues(root["__R"])
  val floors = rows.mapNotNull { parseFloor(it, ctx) }

  val mainPost = rows.firstOrNull { it is JsonObject && int(it, "lou") == 0L } as? JsonObject
  val hotReplies = orderedValues(mainPost?.get("hotreply")).mapNotNull { parseFloor(it, ctx) }

  val rowsPerPage = int(root, "__R__ROWS_PAGE")?.takeIf { it != 0L } ?: DEFAULT_FLOOR_ROWS_PER_PAGE
  val totalRows = int(root, "__ROWS") ?: floors.size.toLong()

  return TopicDetail(
    tid = int(topic, "tid") ?: 0,
    subject = text(topic, "subject") ?: UNTITLED,
    boardName = (root["__F"] as? JsonObject)?.let { str(it, "name") },
    page = (int(root, "__PAGE") ?: 1).toInt(),
    totalRows = totalRows,
    rowsPerPage = rowsPerPage.toInt(),
    totalPages = pageCount(totalRows, rowsPerPage),
    attachBase = attachBase,
    floors = floors,
    hotReplies = hotReplies,
    users = users,
    source = source,
  )
}

private val anonymousContextSeq = AtomicLong(0)

internal fun nextAnonymousContext(): String {
  val seq = anonymousContextSeq.incrementAndGet()
  return "${System.currentTimeMillis().toString(36)}.${seq.toString(36)}"
}

data class TopicPageSnapshot(
  val tid: Long,
  val page: Int,
  val subject: String,
  val boardName: String? = null,
  val favCode: String? = null,
  val floors: Int,
  val totalPages: Int,
  val payload: String,
)

suspend fun fetchTopicDetail(
  client: NgaClient,
  tid: Long,
  page: Int,
  favCode: String? = null,
  pid: Long? = null,
  authorId: Long? = null,
  onSnapshot: ((TopicPageSnapshot) -> Unit)? = null,
  deferSnapshot: ((() -> TopicPageSnapshot) -> Unit)? = null,
): TopicDetail {
  val request = NgaRequest(
    path = "read.php",
    operation = Operation.READ,
    query = queryOf(
      "tid" to tid,
      "page" to page,
      "fav" to favCode,
      "pid" to pid,
      "authorid" to authorId,
      "v2" to 1,
    ),
    validate = ::rejectNonTopicDetail,
  )
  val result = client.execute(request)

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "帖子详情响应里没有 data", via = result.via)
  }

  val detail = parseTopicDetail(
    data = data,
    context = nextAnonymousContext(),
    source = when (result.via) {
      WEB_FALLBACK_STRATEGY_NAME -> TopicSource.WEB
      TOPIC_CACHE_STRATEGY_NAME -> TopicSource.CACHE
      else -> TopicSource.NATIVE
    },
  )

  val key = if (result.via == TOPIC_CACHE_STRATEGY_NAME) null else topicCacheKeyOf(request)
  if ((deferSnapshot != null || onSnapshot != null) && key != null) {
    val createSnapshot = {
      TopicPageSnapshot(
        tid = key.tid,
        page = key.page,
        subject = detail.subject,
        boardName = detail.boardName,
        favCode = favCode,
        floors = detail.floors.size,
        totalPages = detail.totalPages,
        payload = serializeEnvelope(result.envelope),
      )
    }
    if (deferSnapshot != null) deferSnapshot(createSnapshot) else onSnapshot?.invoke(createSnapshot())
  }
  return detail
}
