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

/**
 * 主题列表的解析(API 文档 §2)。直译 `src/core/api/topic-list.ts`。
 *
 * `thread.php` 一个端点覆盖版块列表 / 搜索 / 收藏夹 / 某人的主题,响应形状都一样:
 *
 * ```jsonc
 * { "data": {
 *     "__T":  { "0": { "tid": 1, "subject": "…", "type": 8192, "parent": { "0": 275, "2": "父版面名" } } },
 *     "__F":  { "fid": -7, "name": "网事杂谈", "topped_topic": 3593852, "sub_forums": { … } },
 *     "__ROWS": 10008547, "__T__ROWS_PAGE": 35 } }
 * ```
 *
 * 这里的坑比分类树多一档:**同一个字段会换类型**(`parent` 2024-04 从对象变成字符串化
 * JSON,`replies` 有时是数字有时是字符串)。所以每个字段都单独容错,坏了就退到缺省值,
 * 绝不让一条主题带崩整页——被封时这一页是用户唯一能看到的东西。
 */

/** `type` 位掩码(API 文档 §2 解析要点 3,取自 NGA 官方前端的 PB 表)。 */
private const val TYPE_LOCKED = 1024L
private const val TYPE_ATTACHMENT = 8192L
private const val TYPE_COLLECTION = 0x8000L
private const val TYPE_BOARD_MIRROR = 0x200000L

/** 服务端不给页大小时的缺省值:NGA 的主题列表固定 35 条一页。 */
const val DEFAULT_TOPIC_ROWS_PER_PAGE = 35

/** 没标题的主题(NGA 允许)在列表里的占位。 */
private const val UNTITLED = "无标题"

/** fav 码(CONTEXT.md「fav 码」)藏在 `tpcurl` 的 query 里。 */
private val FAV_PATTERN = Regex("[?&]fav=([0-9a-fA-F]+)")
private val TPCURL_TID_PATTERN = Regex("[?&]tid=(\\d+)")

/**
 * `parent` 字符串化 JSON 那一档要再解一次;这里只解形状,不认字段——
 * 用的是端点层那档宽容的 `NgaJson`,与信封层的严格档不是一回事。
 */
private fun reparse(text: String): JsonElement? =
  runCatching { NgaJson.parseToJsonElement(text) }.getOrNull()

/**
 * TS 的 `unknown`:同一个字段服务端发过字符串、发过数字、也缺席过。
 * 票 10 的 `parseTopicMisc` / `decodeTitleStyle` 收的就是这个,所以在这里做一次归一。
 */
internal fun unknownOf(value: JsonElement?): Any? = when {
  value == null || value is JsonNull -> null
  value is JsonPrimitive ->
    if (value.isString) value.content else value.content.toDoubleOrNull() ?: value.content
  else -> value
}

/**
 * `parent` 有两种形态:对象 `{0:fid,1:stid,2:name}`,以及 2024-04 之后的字符串化 JSON。
 * 名字解不出来就当没有——只有名字是要显示的,光有个 id 对用户没意义。
 */
private fun parseParent(raw: JsonElement?): TopicParent? {
  var value = raw
  if (value is JsonPrimitive && value.isString) {
    value = reparse(value.content) ?: return null
  }
  if (value !is JsonObject) return null

  val name = str(value, "2") ?: return null
  // fid 过一道符号还原:NGA 的版块 id 可以是负数。stid 是主题 id,不适用
  val fid = nonZero(signedBoardId(int(value, "0")))
  val stid = nonZero(int(value, "1"))

  return TopicParent(fid = fid, stid = stid, name = name)
}

/**
 * 快捷方式行指向哪儿。合集的 stid 就是它自己的 tid(合集本身是一个主题),
 * 版块镜像的目标 fid 在 `topic_misc` / `topic_misc_var` 里(API 文档 §2 解析要点 3)。
 */
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

/**
 * `searchpost=1` 时每条主题多出来的 `__P`(API 文档 §2):那条回复本身。
 * 没有 `pid` 的不算——过期占位行的 `__P` 也带 pid,那种由 `denied` 单独标。
 */
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
  // 真实 tid 看 quote_from(API 文档 §2 解析要点 1):quote_from 非 0 时
  // tid 字段反而是引用来源。两个都没有就退回 tpcurl 里的那个。
  val tid = nonZero(int(raw, "quote_from"))
    ?: nonZero(int(raw, "tid"))
    ?: tpcurl?.let { TPCURL_TID_PATTERN.find(it)?.groupValues?.get(1)?.toLongOrNull() }
    ?: return null

  val misc = parseTopicMisc(unknownOf(raw["topic_misc"]))
  // topic_misc_var 是服务端预解析好的同一份东西,topic_misc 是空串时靠它兜底
  val miscVar = raw["topic_misc_var"] as? JsonObject
  val stid = misc.stid ?: miscVar?.let { nonZero(int(it, "2")) }
  // `topic_misc` 那条路已经在 TLV 解码时还原过符号;`topic_misc_var` 是服务端预解析的
  // 同一份东西,它有没有丢符号我们说了不算,所以这一路也过一道
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
    // `denied:"1"` 是服务端拒给内容的标记(帖子过期/无权限),此时 subject 就是拒绝理由
    denied = str(raw, "denied") == "1",
  )
}

/**
 * 版块身份:**stid 优先于 fid**(CONTEXT.md「合集」)。`__F` 与 `sub_forums` 两处
 * 的字段名完全不同,但认 id 的规则得是同一条,所以两边都从这里出。
 */
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

/**
 * 子版块(`__F.sub_forums`):值是 `{0:id, 1:名字, 2:副标题, 3:filter_id, 4:订阅状态码}`,
 * **key 以 `t` 开头表示这是合集**(值里的 id 是 stid,见调研报告 §2)。
 *
 * 第 3、4 项是订阅/屏蔽要用的:`filter_id` 是操作对象,
 * 有没有这一项还决定 `user_option` 的 `type`(进而决定 add/del 哪个是订阅)。
 * 判定与操作都在 `SubBoard.kt`,这里只如实带出来。
 */
private fun parseSubBoard(key: String, raw: JsonElement?): SubBoard? {
  if (raw !is JsonObject) return null
  val name = str(raw, "1") ?: return null
  val collection = key.startsWith("t")
  // 合集那一档的 id 是主题 id,不能套版块 id 的符号还原
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

/** 当前版块(`__F`)。 */
private fun parseBoard(raw: JsonElement?): Board? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null
  val board = boardIdentity(
    name = name,
    fid = nonZero(signedBoardId(int(raw, "fid"))),
    stid = nonZero(int(raw, "stid")),
  ) ?: return null
  // 版头(CONTEXT.md):`topped_topic` 存版头帖 tid,没有时是 0 或空串
  return board.copy(head = nonZero(int(raw, "topped_topic")))
}

/**
 * `thread.php` 的响应里,这些键任意一个在场就说明「服务端确实按主题列表回了话」。
 *
 * 空版块也会有 `__T:{}` 与 `__F`——**一条主题都没有 ≠ 没有这个结构**。
 * 一个都没有的响应根本不是这个接口的东西(被限流、被拦、或者轮换到了一个
 * 我们没验证过的格式档),2026-08-13 之前这种响应会一路变成「这个版块还没有主题」。
 */
private val TOPIC_LIST_STRUCTURE_KEYS = listOf("__T", "__F", "__ROWS")

/** 这份 `data` 是不是一页主题列表的形状。 */
fun hasTopicListStructure(data: JsonElement?): Boolean =
  data is JsonObject && TOPIC_LIST_STRUCTURE_KEYS.any { it in data }

/** 形状不对时的说明,两处(链内 `validate` 与链外兜底)共用同一句话。 */
private const val NOT_A_TOPIC_LIST = "响应里没有主题列表结构（多半是被限流或拦截了）"

/**
 * 反封锁链的一票否决([NgaRequest.validate]):所有走 `thread.php` 的调用都挂它。
 *
 * 有了它,「能洗成 JSON 但不是主题列表」的响应会被当成 `kind = PARSE` 继续轮换,
 * **坏组合也就进不了成功组合缓存**——否则一次瞬时失败就能把 `thread.php` 这条
 * 缓存记录钉死在坏组合上,版块/搜索/收藏夹/热帖一起空到进程重启为止。
 */
fun rejectNonTopicList(envelope: NgaEnvelope): String? {
  // 假错误(「2048:没有符合条件的结果」= 翻到底了)是正常终止,不是坏组合
  if (envelope.fakeError != null) return null
  val data = envelope.data
  if (data !is JsonObject) return "响应里没有 data"
  return if (hasTopicListStructure(data)) null else NOT_A_TOPIC_LIST
}

/**
 * 服务端明确回了「2048:没有符合条件的结果」(假错误白名单)时的空列表。
 *
 * 和「我们根本没拿到列表」不是一回事,所以 [TopicList.listStructure] 是 true:
 * 服务端把话说清楚了,只是内容为空。
 */
fun serverEmptyTopicList(): TopicList = parseTopicList(null).copy(listStructure = true)

/**
 * 解一页主题列表。传的是响应的 `data`。
 *
 * 整页解不出来也不抛:主题列表是无限滚动的,某一页坏掉应该只是「这页没东西」,
 * 上层拿 `topics.isEmpty()` 判断要不要停止翻页;**到底是「没帖」还是「没拿到」
 * 看 [TopicList.listStructure]**。
 */
fun parseTopicList(data: JsonElement?): TopicList {
  val root = data as? JsonObject ?: JsonObject(emptyMap())

  val topics = orderedValues(root["__T"]).mapNotNull(::parseTopic)

  val forum = root["__F"] as? JsonObject
  val board = parseBoard(forum)
  // sub_forums 的 key 是 fid/stid 而不是下标,按数字排会把版块顺序打乱;
  // 服务端下发的顺序就是版块想要的展示顺序,原样保留(TS 用的是 Object.entries)
  val subForums = (forum?.get("sub_forums") as? JsonObject)?.let(::jsOwnEntries).orEmpty()
  val subBoards = subForums.mapNotNull { (key, raw) -> parseSubBoard(key, raw) }

  val rowsPerPage = (nonZero(int(root, "__T__ROWS_PAGE")) ?: DEFAULT_TOPIC_ROWS_PER_PAGE.toLong())
  // `__ROWS` 在「某人的回复」里是**空串**(服务端不算这个总数),`int` 会把它读成 0,
  // 直接用就变成「总共 0 条 / 共 1 页」。空/0 时退到本页条数 `__T__ROWS`。
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

/** `Math.max(1, Math.ceil(total / perPage))`;每页 0 条时按 1 页算(除零在 JS 里是 Infinity)。 */
internal fun pageCount(totalRows: Long, rowsPerPage: Long): Int {
  if (rowsPerPage <= 0) return 1
  return max(1.0, ceil(totalRows.toDouble() / rowsPerPage.toDouble())).toInt()
}

/**
 * 把无限滚动攒下的几页拼成一条列表。
 *
 * 必须按 tid 去重:置顶主题与版块镜像行**每页都会再回来一次**
 * (实测 fid=-7 第 1、2 页重叠 20 条),不去重列表会反复出现同一行。
 */
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

/** 主题列表的排序(功能文档 §2.2)。默认按最后回复,spec §4 定的默认值。 */
enum class TopicSort { LAST_POST, POST_DATE }

/**
 * 拉一页主题列表(`POST thread.php`,API 文档 §2)。
 *
 * `data` 为空、或者压根不是主题列表的形状(被封、被限流、轮换到了没验证过的格式档)
 * 时抛 `kind = PARSE`,交给上层走反封锁链兜底;
 * 有 `__T` 但一条都解不出来不算错——版块本来就可能是空的。
 */
suspend fun fetchTopicList(
  client: NgaClient,
  /** 版块 id:合集传 stid、普通版块传 fid,二选一(CONTEXT.md「合集」) */
  boardId: Long,
  kind: BoardKind,
  /** 从 1 起 */
  page: Int,
  sort: TopicSort = TopicSort.LAST_POST,
  /**
   * 精华区(功能文档 §2.2):`recommend=1`。Android 客户端还固定带
   * `order_by=postdatedesc&user=1`(API 文档 §2),精华区下 [sort] 不生效。
   */
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
  // 链内的 validate 已经拦过一道,这里再拦一次是为了让 fetchTopicList 的契约
  // 不依赖调用方传对了 validate(比如别处直接拿 direct 策略打这个接口)
  if (!hasTopicListStructure(data)) {
    throw NgaError(NgaErrorKind.PARSE, NOT_A_TOPIC_LIST, via = result.via)
  }
  return parseTopicList(data)
}
