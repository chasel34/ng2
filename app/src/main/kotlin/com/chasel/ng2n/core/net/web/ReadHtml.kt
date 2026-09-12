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

/**
 * `read.php` 网页版 → 与 `__output=8` 同构的信封(Web 反解,ADR-0002 / API 文档 §0.8)。
 * 直译 `src/core/net/web/read-html.ts`。
 *
 * 不带格式参数请求 `read.php` 拿到的是给浏览器看的整页 HTML,但**数据并没有被渲染死**:
 * 网页版自己也是靠内联 JS 把数据交给前端渲染的,所以逐楼数据、用户表、分页、错误码
 * 都以结构化的形态躺在 `<script>` 里。这里把它们抠回来,拼成 `core/api` 的
 * `parseTopicDetail` 本来就吃的那个 `data` 形状——反解成功后下游一行都不用改。
 *
 * 四个数据源(名字即 API 文档 §0.8 列的那几个):
 *
 * | 内联 JS | 给出什么 |
 * |---|---|
 * | `commonui.postArg.proc(…)` | 逐楼元数据(pid / 楼号 / 作者 / 时间 / 赞数 / 发帖设备),正文与标题以元素 id 间接给出 |
 * | `commonui.userInfo.setAll({…})` | 用户表,与 JSON 的 `__U` **同构**(连 `__GROUPS` / `__MEDALS` 附表和匿名槽位都一样) |
 * | `var __PAGE` / `postArg.setDefault(…)` | 分页与主题元数据 |
 * | `<!--msgcodestart-->` | 服务端错误(找不到主题、权限不足…) |
 *
 * ## 已知不可恢复的三个字段(网页版本来就不给,**别试图补**)
 *
 * 1. **投票**(`vote`)拿不到:网页版把投票模块交给另一段 JS 渲染,`proc` 的实参里没有。
 * 2. **贴条与热门回复的发帖设备**(`from_client`)恒为空串:网页版只给楼层,不给嵌套的那些。
 * 3. **匿名楼主在第 2 页及以后认不出「楼主」标记**:`#anony_` 串只在用户表里出现,
 *    而主楼不在场时没有别的线索指向楼主(JSON 路线每页都带 `__T.author`)。
 *
 * ## 参数位置表的出处
 *
 * [ArgIndex] 那张表全网无第二份文档,是移植前必须重新验证的东西(spec §七.5)。
 * **2026-08-22(票 08)对线上重验过**:同一时刻同一主题并发抓「网页 HTML」与
 * 「`__output=8` JSON」两份,20 楼 × 10 个可恢复字段逐条对齐,零差异——表没变,原样移植。
 * 对拍语料在金样本 `web/revalidate-45150945`,TS 侧的对拍用例在
 * `src/core/net/web/read-html.test.ts`。
 */

/** `read.php` 固定每页 20 楼;`__PAGE` 与 `setDefault` 都拿不到时的兜底。 */
private const val DEFAULT_ROWS_PER_PAGE = 20

/**
 * `commonui.postArg.proc(…)` 的实参位置表。
 *
 * 前 8 个是 key + 7 个 DOM 元素,之后是数据。位置由三份真实抓包与同一时刻的
 * `__output=8` 响应逐字段对齐得出(fixture `read-web-*` 与 `core/api/…/read-*`),
 * 没对上的位置一律不猜。
 */
private object ArgIndex {

  /** 楼号(数字)或 `'_<pid>'`(贴条)/ `'__<pid>'`(热门回复) */
  const val KEY = 0
  const val SUBJECT_ELEMENT = 2
  const val CONTENT_ELEMENT = 3
  const val INFO_ELEMENT = 6
  const val PID = 10

  /** 楼层类型位(JSON 的 `type`),匿名等状态在里面 */
  const val TYPE = 11

  /** 作者 id,字符串;匿名是 `'-1'` 这种页内序号 */
  const val AUTHOR_ID = 13
  const val POSTED_AT = 14

  /** `'<score_2>,<score>,<recommend>'`——只有中间那位(赞数)与 JSON 对齐验证过 */
  const val SCORES = 15
  const val CONTENT_LENGTH = 16
  const val FROM_CLIENT = 19
}

/** `data.__R` 里一条楼层记录。键名与 JSON 路线完全一致(下游按这些名字取)。 */
private typealias PostRecord = LinkedHashMap<String, JsonElement>

/** 用户表那一整块 JS 就是 JSON,但和 JSON 接口一样不合法,得先洗一遍(见 [parseUserTable])。 */
private val UserTableJson = Json {
  isLenient = false
  ignoreUnknownKeys = false
  allowSpecialFloatingPointValues = false
}

/**
 * JS 的 `number` 落进 JSON 时的写法:`JSON.stringify(43)` 是 `43` 而不是 `43.0`。
 * 整数值一律写成整数,否则金样本上会因为「1 vs 1.0」以外的场合(如 `String(n)`)分家。
 */
private fun jsonNumber(value: Double): JsonPrimitive =
  if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
    JsonPrimitive(value.toLong())
  } else {
    JsonPrimitive(value)
  }

/** `String(n)`:整数不带小数点。 */
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

/** `^-?\d+$` —— TS 版 `numberOf` 认字符串数字的那条判据。 */
private val INTEGER_STRING = Regex("""^-?\d+$""")

private fun numberOf(argument: JsArgument?): Double? = when {
  argument is JsArgument.Num -> argument.value
  argument is JsArgument.Str && INTEGER_STRING.matches(argument.value) -> argument.value.toDouble()
  else -> null
}

private val TAGS = Regex("<[^>]*>")

/** 只留文本:日期躺在 `<span title='reply time'>…</span>` 里。 */
private fun stripTags(html: String): String = TAGS.replace(html, "").jsTrim()

private val TRAILING_SLASHES = Regex("/+$")

/**
 * `__ATTACH_BASE_VIEW` 在网页版里**只有域名**(`img.nga.cn`),
 * 而 JSON 的 `__GLOBAL._ATTACH_BASE_VIEW` 带路径(`img.nga.cn/attachments`)。
 * 补齐这一段路径,下游 `normalizeAttachBase` 才拼得出图片地址。
 */
private fun attachBaseOf(html: String): String? {
  val raw = readStringVariable(html, "__ATTACH_BASE_VIEW")
  if (raw == null || raw.jsTrim().isEmpty()) return null
  val value = TRAILING_SLASHES.replace(raw.jsTrim(), "")
  return if (value.contains('/')) value else "$value/attachments"
}

/**
 * 用户表。网页版这段 JS 就是一整块 JSON,且键名、附表(`__GROUPS` / `__MEDALS` /
 * `__REPUTATIONS`)、匿名槽位(`"-1"`)与 JSON 接口的 `__U` 逐字段相同——
 * 所以这一档最省事:洗一遍直接当 `__U` 交下去。
 *
 * 要洗是因为它和 JSON 接口一样不合法:`remark` 字段里带裸 TAB
 * (`sanitizeNgaJson` 第 7 步管的正是这个)。
 */
private fun parseUserTable(html: String): JsonElement {
  val table = findCall(html, "commonui.userInfo.setAll(")?.args?.getOrNull(0)
  if (table !is JsArgument.Expression) return JsonObject(emptyMap())
  return try {
    when (val parsed = UserTableJson.parseToJsonElement(sanitizeNgaJson(table.text))) {
      // TS 的 `typeof parsed === 'object'` 把数组也算进来,照收
      is JsonObject, is JsonArray -> parsed
      else -> JsonObject(emptyMap())
    }
  } catch (_: Exception) {
    // 用户表解不出来不该毁掉整页:楼层照样能渲染,只是作者名要退回 uid
    JsonObject(emptyMap())
  }
}

/** `ubbcode.attach.load('postattach0','postcontent0',[{…}])` → 正文元素 id ↦ `attachs`。 */
private fun parseAttachments(html: String): Map<String, JsonObject> {
  val byContentId = LinkedHashMap<String, JsonObject>()
  for (call in findCalls(html, "ubbcode.attach.load(")) {
    val contentId = stringOf(call.args.getOrNull(1))
    val list = call.args.getOrNull(2)
    if (contentId == null || list !is JsArgument.Expression) continue

    val attachs = LinkedHashMap<String, JsonElement>()
    // 索引用 `forEach` 的下标而不是「收下的第几条」:没有 url 的那条被跳过,
    // 但它占掉的序号 TS 版也不会补回来,键上因此可能有洞——照抄
    parseObjectLiterals(list.text).forEachIndexed { index, item ->
      val url = item["url"] ?: return@forEachIndexed
      val fields = LinkedHashMap<String, JsonElement>()
      // 字段名对齐 JSON 的 `attachs`:网页版把 `attachurl` 叫 `url`,其余同名
      item.forEach { (key, value) -> fields[key] = JsonPrimitive(value) }
      fields["attachurl"] = JsonPrimitive(url)
      attachs[index.toString()] = JsonObject(fields)
    }
    if (attachs.isNotEmpty()) byContentId[contentId] = JsonObject(attachs)
  }
  return byContentId
}

private val ALERT_CONTAINER = Regex("""^alertc(\d+)$""")

/** `commonui.loadAlertInfo('[E… ]','alertc3')` → 楼号 ↦ `alterinfo`(非空即「被编辑过」)。 */
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

/**
 * 贴条与热门回复都嵌在所属楼层的 HTML 里,靠外面那层 `<span>` 的 id 区分:
 * `comment_for_<pid>` 是贴条,`hightlight_for_<楼号>` 是热门回复(NGA 自己的拼写)。
 *
 * 按「离调用点最近的那个标记」判定,而不是认 `proc` 第一个实参的 `_` / `__` 前缀——
 * 后者只是拼 DOM id 的副产物,标记 span 才是网页版真正用来分区的东西。
 */
private fun nestedKindAt(html: String, at: Int): NestedKind {
  val note = html.lastIndexOf("id='comment_for_", at)
  val hot = html.lastIndexOf("id='hightlight_for_", at)
  return if (note > hot) NestedKind.NOTE else NestedKind.HOT_REPLY
}

private class ParsedPosts(
  /** `__R`:楼层流,键是页内序号(同 JSON) */
  val rows: LinkedHashMap<String, PostRecord>,
  /** 楼号 0 那一楼的作者 key,认「楼主」用 */
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
  // `'0,43,0'` 的中间那位是赞数;两侧(score_2 / recommend)没有对齐验证过,不往下传
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

/**
 * TS 的 `Number(x)`:`undefined` 与解不出来的串都是 `NaN`,**空串是 0**。
 * 这里只有赞数用得上(`'0,43,0'` 少一位时会落到这条)。
 */
private fun jsNumberOrNaN(raw: String?): Double =
  if (raw == null) Double.NaN else com.chasel.ng2n.core.net.jsNumber(raw)

private fun parsePosts(html: String): ParsedPosts {
  val attachments = parseAttachments(html)
  val alterInfo = parseAlterInfo(html)
  val rows = LinkedHashMap<String, PostRecord>()
  // 贴条/热门回复的 `proc` 排在所属楼层**之前**(它们嵌在那一楼的 HTML 里),
  // 所以先攒着,等到下一条楼层记录出现时挂上去
  var pendingNotes = ArrayList<PostRecord>()
  var pendingHotReplies = ArrayList<PostRecord>()
  var index = 0
  var starterAuthorId: String? = null
  /** pid ↦ 楼号,给热门回复补它在本页的楼号用 */
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

  // 热门回复的楼号网页版没直接给,但它就是本页某一楼——按 pid 认回来。
  // TS 版是就地改那条记录,所以要在挂上去之后才补;这里的 [PostRecord] 同样是可变的。
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

/** JS 的数组展开进对象:`{ ...[a, b] }` → `{ "0": a, "1": b }`。 */
private fun indexed(records: List<PostRecord>): JsonObject {
  val out = LinkedHashMap<String, JsonElement>()
  records.forEachIndexed { index, record -> out[index.toString()] = JsonObject(record) }
  return JsonObject(out)
}

private fun postPid(record: PostRecord): Double =
  (record["pid"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Double.NaN

private class Pagination(val page: Long, val rowsPerPage: Long, val totalRows: Long)

private val PAGE_VAR = Regex("""var\s+__PAGE\s*=\s*\{([^}]*)\}""")

/**
 * 分页。两个来源互相校准:
 *
 * - `var __PAGE = {0:'…',1:15,2:1,3:20}` —— 总页数 / 当前页 / 每页楼数,
 *   这是网页版页码条自己用的那份,**跟着「只看某人」这类过滤走**。
 * - `postArg.setDefault(…, type, replies, lastpost, 每页楼数)` —— 末四位里的 `replies`
 *   是主题回复总数(`__ROWS = replies + 1`,三份抓包都对得上),比页数精确。
 *
 * 两者一致就用精确值;不一致(过滤视图)以 `__PAGE` 的页数为准,
 * 总楼数退回「页数 × 每页」——宁可偏大,也不能让页码条少一页翻不过去。
 */
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
    // `Math.max(1, Math.ceil(rows / rowsPerPage))`:每页 0 楼时 TS 得到 Infinity,
    // 与任何页数都不相等而落到下面那条兜底——用 Double 算才留得住这个行为
    val pages = maxOf(1.0, ceil(rows.toDouble() / rowsPerPage.toDouble()))
    if (totalPages == null || pages == totalPages.toDouble()) {
      return Pagination(page, rowsPerPage, rows)
    }
  }
  return Pagination(page, rowsPerPage, (totalPages ?: 1L) * rowsPerPage)
}

/** `setDefault` 末四位是 `type, replies, lastpost, 每页楼数`,从尾巴数比从头数稳。 */
private fun setDefaultReplies(html: String): Long? {
  val call = findCalls(html, "commonui.postArg.setDefault(").firstOrNull() ?: return null
  val value = numberOf(call.args.getOrNull(call.args.size - 3)) ?: return null
  return if (value.isFinite()) value.toLong() else null
}

/** `setDefault` 第 4 位是楼主 id(匿名时是 `-3` 这类页内序号)。 */
private fun setDefaultStarterId(html: String): Double? {
  val call = findCalls(html, "commonui.postArg.setDefault(").firstOrNull() ?: return null
  return numberOf(call.args.getOrNull(3))
}

private class ServerMessage(val code: String, val info: String)

/** `<!--msgcodestart-->2048<!--msgcodeend-->` + `<!--msginfostart-->找不到主题<!--msginfoend-->`。 */
private fun readMessage(html: String): ServerMessage? {
  val code = readMarkedSection(html, "msgcode")?.jsTrim() ?: return null
  return ServerMessage(code, readMarkedSection(html, "msginfo")?.jsTrim() ?: "")
}

/**
 * 反解一页 `read.php` 网页版 HTML。
 *
 * 契约与 `parseNgaJson` 一致:解析不出来抛 `kind = PARSE`(可重试,链继续往下走),
 * 服务端语义错误抛 `kind = SERVER`(不重试),命中假错误白名单的当成功。
 */
fun parseReadPageHtml(html: String, via: String? = null): NgaEnvelope {
  val message = readMessage(html)
  if (message != null) {
    // JSON 路线的 `error.0` 就是 `"2048:找不到主题"`,这里拼成同一句,
    // 好让错误页无论走哪条路线都显示同样的话
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
  // 楼主名认「楼主」标记用。实名从用户表取;**匿名只有第 1 页认得出**——
  // `#anony_` 串只出现在用户表里,而第 2 页起主楼不在场,没有别的线索指向楼主。
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
