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

/**
 * 帖子详情的解析(API 文档 §3,`read.php`)。直译 `src/core/api/topic-detail.ts`。
 *
 * 一页响应里有四张互相引用的表:
 *
 * ```jsonc
 * { "data": {
 *     "__GLOBAL": { "_ATTACH_BASE_VIEW": "img.nga.cn/attachments" },  // 附件域名,每次都读
 *     "__U":  { "66313282": { …用户… }, "-1": { "username": "#anony_…" } },
 *     "__R":  { "0": { "lou": 0, "authorid": -1, "content": "…", "comment": { … } } },
 *     "__T":  { "tid": …, "subject": "…", "author": "…" },
 *     "__ROWS": 242, "__R__ROWS_PAGE": 20 } }
 * ```
 *
 * 三处非显然的地方,都有单测钉着:
 *
 * 1. **匿名楼层的 `authorid` 是 `-1`、`-2` 这种页内序号**,不是 uid。不同页的 `-1`
 *    是不同的人,所以用户 key 一律加请求级前缀(API 文档 §3 最后一段)。
 * 2. **贴条在 `__R` 里占一条幽灵行**:只有 `subject`/`comment_to_id`、没有 `content`,
 *    真身挂在被贴楼层的 `comment` 下。不滤掉就会多渲染一个空楼层。
 * 3. **`attachs` 经常是空串而不是对象**,`avatar` 可能是 JSON 串(API 文档 §3 用户字段)。
 */

/** `read.php` 固定每页 20 楼(API 文档 §3)。 */
private const val DEFAULT_FLOOR_ROWS_PER_PAGE = 20L

/** 禁言 buff(`ForumConstants.BUFF_MUTE_IDS`)。 */
private val MUTE_BUFF_IDS = listOf("105", "117")

/** `yz` 的这个取值表示账号被 nuke。别的负值(如 -5)是另外的状态。 */
private const val NUKED_YZ = -1L

/** 没标题的主题(NGA 允许)。与主题列表用同一个占位。 */
private const val UNTITLED = "无标题"

/** avatar 是 JSON 串时,抠出里面第一个 http 地址(字段里的 `\/` 要先还原)。 */
private val AVATAR_URL_PATTERN = Regex("https?://[^\"',\\s\\\\]+")
private val HTTP_PREFIX = Regex("^https?://")

/**
 * 头像地址。服务端这个字段有三种形态:普通 URL、空串、以及一坨 JSON
 * (`js_escap_avatar`,多套头像并存时)。JSON 那种取第一个 http URL。
 */
fun parseAvatarUrl(raw: JsonElement?): String? {
  if (raw !is JsonPrimitive || !raw.isString) return null
  val value = raw.content.replace("\\/", "/").trim()
  if (value.isEmpty()) return null
  if (HTTP_PREFIX.containsMatchIn(value)) return value
  return AVATAR_URL_PATTERN.find(value)?.value
}

/**
 * 发帖设备(`from_client`)。取值像 `"8 Android"` / `"7 iOS"` / `"31 /"`——
 * 编号会随客户端版本变,认名字比认编号稳。
 */
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

/**
 * 用户表的 key:实名用 uid 字符串,**匿名加请求级前缀**。
 * 前缀形式 `<context>,-1` 与 MNGA 一致,方便对拍。
 */
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
  // 匿名用户的 uid 字段是 0,不是真身
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
    // 签名是 BBCode(字段名 signature/sign 都见过,API 文档 §3);空串 = 没设置
    signature = str(raw, "signature") ?: str(raw, "sign"),
    reputation = (int(raw, "rvrc") ?: int(raw, "fame") ?: 0L) / REPUTATION_SCALE,
    postCount = int(raw, "postnum") ?: int(raw, "posts") ?: 0L,
    muted = parseMuted(raw["buffs"]),
    nuked = int(raw, "yz") == NUKED_YZ,
  )
}

/** `__GROUPS`:memberid → 用户组名(设计稿里的「级别」)。 */
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

/** 一条附件(`attachs` 的成员)。`attachs` 是空串时上层根本不会调到这里。 */
private fun parseAttachment(raw: JsonElement?, base: String): FloorAttachment? {
  if (raw !is JsonObject) return null
  val attachUrl = str(raw, "attachurl") ?: return null

  val url = "$base/${attachUrl.trimStart('/')}"
  // thumb 在旧客户端里判的是 `== "1"`,实测服务端给的是缩略图尺寸(56/120)。
  // 判「有值且不是 0」才对得上现在的响应——字符串 "0" 也要算没有,
  // 否则会拼出一个不存在的 .thumb.jpg,宫格里就是一格加载失败。
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
  /** 楼主身份:实名比 uid、匿名比 `#anony_` 串(同一人在同一主题里的串是固定的) */
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

/**
 * 一个楼层。贴条与热门回复是同一个结构,所以共用这个函数(贴条不再递归解贴条)。
 *
 * 返回 null 的两种情况:不是对象,或者**没有 `content` 字段**——后者就是
 * 贴条在 `__R` 里留下的幽灵行,真身已经挂在被贴楼层下面了。
 */
private fun parseFloor(raw: JsonElement?, ctx: FloorContext, depth: Int = 0): Floor? {
  if (raw !is JsonObject) return null
  val content = raw["content"]
  if (content !is JsonPrimitive || !content.isString) return null

  val authorId = int(raw, "authorid") ?: 0
  val authorKey = userKey(authorId.toString(), ctx.context)
  // TS 是 `isRecord(attachs)`:空串那一档在这里被挡掉。**真数组不认**——照抄 RN 版
  // (`__output=11` 若把 attachs 发成真数组会静默丢附件,已记进票 07 的「票外发现」)
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
    // alterinfo 非空 = 被编辑过(API 文档 §3);内容是编辑记录,本票不展开
    edited = str(raw, "alterinfo") != null,
    client = parseClient(raw["from_client"]),
    attachments = attachments,
    notes = notes,
    vote = str(raw, "vote"),
  )
}

/**
 * 解一页帖子详情。传的是响应的 `data`。
 *
 * 和主题列表一样,整页解不出来也不抛:被封时这一页是用户唯一能看到的东西,
 * 上层拿 `floors.isEmpty()` 判断要不要走兜底。
 *
 * @param context 请求级 context,用来给匿名用户 id 加前缀。**同一次请求内必须一致、
 *   不同请求之间必须不同**——否则第 2 页的 `-1` 会和第 1 页的 `-1` 串成同一个人。
 * @param source 数据来源(ADR-0002 的 Web 反解档要在详情页出提示条)
 */
fun parseTopicDetail(
  data: JsonElement?,
  context: String,
  source: TopicSource = TopicSource.NATIVE,
): TopicDetail {
  val root = data as? JsonObject ?: JsonObject(emptyMap())

  val attachBase = normalizeAttachBase(
    unknownOf((root["__GLOBAL"] as? JsonObject)?.get("_ATTACH_BASE_VIEW")),
  )

  // `__U` 里除了用户,还混着 `__GROUPS` / `__MEDALS` / `__REPUTATIONS` 三张附表
  // (不是嵌在 data 顶层,实测就在 __U 内部);带 `__` 前缀的 key 一律不是用户。
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
    // 匿名主题的 __T.authorid 是页内序号(实测 -3),认不得人,只有 author 串可信
    starterUid = if (starterUid == null || starterUid < 0) null else starterUid,
    starterRawName = str(topic, "author"),
    users = users,
  )

  val rows = orderedValues(root["__R"])
  val floors = rows.mapNotNull { parseFloor(it, ctx) }

  // 热门回复只挂在主楼上(API 文档 §3)
  val mainPost = rows.firstOrNull { it is JsonObject && int(it, "lou") == 0L } as? JsonObject
  val hotReplies = orderedValues(mainPost?.get("hotreply")).mapNotNull { parseFloor(it, ctx) }

  // TS 是 `int(root,'__R__ROWS_PAGE') || 20`——JS 的 `||` 把 0 也当假,照抄
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

/**
 * 每次请求换一个匿名 context。
 *
 * 只要求「同一次请求内一致、不同请求之间不同」,不需要密码学随机——
 * 时间戳 + 自增计数在 core 里没有平台依赖,也不会因为同一毫秒发两个请求撞号。
 */
private val anonymousContextSeq = AtomicLong(0)

internal fun nextAnonymousContext(): String {
  val seq = anonymousContextSeq.incrementAndGet()
  return "${System.currentTimeMillis().toString(36)}.${seq.toString(36)}"
}

/**
 * 一页缓存的全部内容:正文(序列化后的信封)+ 列表页要显示的元数据。
 *
 * 存储侧的孪生体是 `data/cache/TopicCacheRepository.kt` 的 `CachedPageSnapshot`
 * (票 14):core 层不认识 Room,所以两边各有一份,由 `data/net/TopicCachePayloadReader.kt`
 * 负责搬运。字段一一对应,改一边就要改另一边。
 */
data class TopicPageSnapshot(
  val tid: Long,
  /** 从 1 起 */
  val page: Int,
  val subject: String,
  val boardName: String? = null,
  /** fav 码(CONTEXT.md「fav 码」),离线重开隐藏/过期主题时要带回去 */
  val favCode: String? = null,
  /** 这一页有多少楼 */
  val floors: Int,
  val totalPages: Int,
  /** 序列化后的信封,原样喂给缓存档即可还原 */
  val payload: String,
)

/**
 * 拉一页帖子详情(`POST read.php`,API 文档 §3)。
 *
 * UA 档不在这里写死:`read.php` 用 `WINDOWS_PHONE` 档(MNGA 强制用它,实测更不容易被封)
 * 是**策略开关**,由设备侧的设置决定(ADR-0002;`NetworkSettingsSource.readPhpUserAgent`,
 * 默认就是它)——被封的表现会随时间变,这一档要能关。
 *
 * @param pid 只看某一楼(API 文档 §3)。从通知或「我的回复」跳过来时用:
 *   服务端**不提供 pid → 页码**的换算,只提供这个「单独把那一楼捞出来」的模式,
 *   响应里只有这一条楼层(且 `lou` 会被重编为 0,不是真实楼层号)。
 * @param authorId 只看某人:服务端只回这个 uid 的楼层,分页随之重排。
 * @param onSnapshot 拿到一页可缓存的数据时回调一次(票 13 的自动缓存)。设备侧把它写进
 *   Room;不传就是不缓存。缓存写在这里而不是包一层 client:要存的正文字节(信封)与要存的
 *   元数据(标题/版块名/楼数/总页数)分别只有请求侧和解析结果知道,这是唯一同时握着两者的地方。
 * @param deferSnapshot 前台阅读页用的延迟缓存入口。回调拿到的是「创建快照」的惰性函数,
 *   调用它时才会序列化信封;这样页面转场期间既不做大字符串序列化,也不碰 SQLite。
 *   两者同时传时**优先延迟入口**。
 */
suspend fun fetchTopicDetail(
  client: NgaClient,
  tid: Long,
  /** 从 1 起 */
  page: Int,
  /** fav 码(CONTEXT.md「fav 码」),访问隐藏/过期主题必带 */
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
      // v2 是 Android v4 的新版结构,_ATTACH_BASE_VIEW 就是它带出来的
      "v2" to 1,
    ),
  )
  val result = client.execute(request)

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "帖子详情响应里没有 data", via = result.via)
  }

  // 反封锁链哪一档出的结果只有 `via` 说得清(票 08 的 Web 反解档会把网页 HTML
  // 反解成同构信封,票 14 的缓存档会从本机还原同一个信封,解析这一步感知不到差别)
  // ——提示条要显示的正是它
  val detail = parseTopicDetail(
    data = data,
    context = nextAnonymousContext(),
    source = when (result.via) {
      WEB_FALLBACK_STRATEGY_NAME -> TopicSource.WEB
      TOPIC_CACHE_STRATEGY_NAME -> TopicSource.CACHE
      else -> TopicSource.NATIVE
    },
  )

  // 缓存档自己吐出来的那份不必再存一次(内容一模一样);
  // 只看该楼/只看某人这类过滤视图 `topicCacheKeyOf` 会挡掉
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
