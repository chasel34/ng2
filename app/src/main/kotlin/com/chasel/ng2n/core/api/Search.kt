package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.signedBoardId
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.gbk
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 搜索三合一(功能文档 §2.7):主题、版块、用户。直译 `src/core/api/search.ts`。
 *
 * 三条路各打各的端点,**关键词编码还不一样**(API 文档 §0.5,2026-08-08 真机对拍):
 *
 * - 主题:`thread.php?key=…`,key 按 **UTF-8** urlencode(配 `__inchst=UTF8`,
 *   传输层默认就带);`fid`/`stid` 限定版块,`content=1` 连正文一起搜。
 * - 版块:`forum.php?key=…`,key 必须按 **GBK** urlencode——实测 UTF-8 的 key
 *   服务端按 GBK 解成乱码,直接「没找到符合条件的版面」。
 * - 用户:没有专门接口,纯数字按 uid、否则按用户名走 ucp 资料查询(`UserProfile.kt`)。
 *
 * 主题结果与版块列表是同一形状(thread.php),解析全部复用 [parseTopicList]。
 */

/**
 * 搜索结果里混着的**服务端提示行**:拒绝理由被塞进 `subject` 当成一条主题下发,
 * 实测有「帐号权限不足」「帐号声望不足」「帖子发布或回复时间超过限制」三种,
 * 一次搜索里能占到三成(2026-08-08 对拍 `key=第六感`:34 条里 10 条)。
 *
 * 认它靠结构而不是文案(文案还会新增):`denied` 标记 + 根本没有作者
 * (`author` 空串、`authorid` 0)。真主题一定有作者,哪怕是匿名串。
 *
 * **只在搜索里滤**。同样的行在「我的回复」「收藏夹」里是有意义的——那是
 * 你回过/收藏过的帖子过期了,列表要照常给出一行(UI 会画成灰色禁止图标)。
 */
private fun isServerNoticeRow(topic: Topic): Boolean =
  topic.denied && topic.author.isEmpty() && topic.authorId == null

private fun withoutServerNotices(list: TopicList): TopicList {
  val topics = list.topics.filterNot(::isServerNoticeRow)
  // `totalRows` 不动:那是服务端给的命中总数,翻页判据要跟它对齐
  return if (topics.size == list.topics.size) list else list.copy(topics = topics)
}

/**
 * 搜一页主题(`POST thread.php?key=…`)。
 *
 * 没有结果(或翻过头)时服务端回「2048:没有符合条件的结果」——假错误白名单里的
 * 一条,归一成空页;上层拿 `topics.isEmpty()` 停止翻页。
 * 实测三种参数组合(全站 / 本版 / 本版含正文)`__ROWS` 都是有效总数,总页数可信。
 *
 * @param key 关键词(原文即可,UTF-8 编码由 query 层做)
 * @param boardId 限定版块:合集传 stid、普通版块传 fid;不传 = 全站(API 文档 §2)
 * @param searchContent `content=1`:连正文一起搜。实测结果仍是普通主题行(没有 `__P`)
 */
suspend fun fetchTopicSearch(
  client: NgaClient,
  key: String,
  page: Int,
  boardId: Long? = null,
  kind: BoardKind = BoardKind.BOARD,
  searchContent: Boolean = false,
): TopicList {
  val result = client.execute(
    NgaRequest(
      path = "thread.php",
      operation = Operation.READ,
      query = queryOf(
        "key" to key,
        "fid" to if (boardId != null && kind == BoardKind.BOARD) boardId else null,
        "stid" to if (boardId != null && kind == BoardKind.COLLECTION) boardId else null,
        "content" to if (searchContent) 1 else null,
        "page" to page,
      ),
      // 和版块列表共用同一条 comboCache 记录,形状校验也必须是同一份,
      // 否则搜索这条路照样能把坏组合喂进缓存
      validate = ::rejectNonTopicList,
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    if (result.fakeError != null) return serverEmptyTopicList()
    throw NgaError(NgaErrorKind.PARSE, "主题搜索响应里没有 data", via = result.via)
  }
  return withoutServerNotices(parseTopicList(data))
}

/** 版块搜索的一条结果:版块本身 + 它挂在哪个上级版块下(结果行的来源标注)。 */
@Serializable
data class BoardSearchItem(val board: Board, val parentName: String? = null)

/**
 * 解析 `forum.php?key=…` 的 `data`:条目直接以数字键挂在 data 上
 * (不像版块收藏包一层 `data["0"]`),每条形如
 * `{fid, stid, name, descrip, relevance, url, parent:{fid,name}}`。
 * 合集也会出现在结果里(`stid` 非 0,此时 `fid` 是宿主版块),身份规则与
 * 分类树同一条:stid 优先。没结果时 data 只剩 `__MESSAGE`,解出空列表。
 */
fun parseBoardSearch(data: JsonElement?): List<BoardSearchItem> =
  orderedEntries(data).mapNotNull { (_, raw) ->
    if (raw !is JsonObject) return@mapNotNull null
    val name = str(raw, "name") ?: return@mapNotNull null

    val fid = nonZero(signedBoardId(int(raw, "fid")))
    val stid = nonZero(int(raw, "stid"))
    val id = stid ?: fid ?: return@mapNotNull null

    BoardSearchItem(
      board = Board(
        id = id,
        kind = if (stid == null) BoardKind.BOARD else BoardKind.COLLECTION,
        fid = fid,
        stid = stid,
        name = name,
        info = str(raw, "descrip"),
      ),
      parentName = (raw["parent"] as? JsonObject)?.let { str(it, "name") },
    )
  }

/**
 * 搜版块(`POST forum.php?key=…`,key 走 GBK——API 文档 §1.2)。
 * 一次给全量(实测上限 100 条、按 relevance 排好),没有分页。
 * 「没找到符合条件的版面」在假错误白名单(`没找到`)里,归一成空列表。
 */
suspend fun fetchBoardSearch(client: NgaClient, key: String): List<BoardSearchItem> {
  val result = client.execute(
    NgaRequest(
      path = "forum.php",
      operation = Operation.READ,
      query = queryOf("key" to gbk(key)),
      validate = ::rejectNonBoardSearch,
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    if (result.fakeError != null) return emptyList()
    throw NgaError(NgaErrorKind.PARSE, "版块搜索响应里没有 data", via = result.via)
  }
  return parseBoardSearch(data)
}

/** 用户搜索的查询方式:纯数字按 uid 查,否则按用户名查(功能文档 §2.7)。 */
@Serializable
sealed interface UserSearchQuery {

  @Serializable
  @SerialName("uid")
  data class Uid(val uid: Long) : UserSearchQuery

  @Serializable
  @SerialName("username")
  data class Username(val username: String) : UserSearchQuery
}

private val ALL_DIGITS = Regex("^\\d+$")

/** JS 的 `Number.MAX_SAFE_INTEGER`。 */
private const val MAX_SAFE_INTEGER = 9007199254740991L

/**
 * 把输入归一成 uid 或用户名。空输入(或全空白)返回 null。
 * 只有「整段都是数字」才算 uid——NGA 用户名可以带数字,混排的一律按名字查;
 * 大到不安全的整数也按名字查(还有机会命中)。
 */
fun parseUserSearchInput(text: String): UserSearchQuery? {
  val trimmed = text.jsTrim()
  if (trimmed.isEmpty()) return null
  if (ALL_DIGITS.matches(trimmed)) {
    val uid = trimmed.toLongOrNull()
    if (uid != null && uid > 0 && uid <= MAX_SAFE_INTEGER) return UserSearchQuery.Uid(uid)
  }
  return UserSearchQuery.Username(trimmed)
}
