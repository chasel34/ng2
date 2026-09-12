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

private fun isServerNoticeRow(topic: Topic): Boolean =
  topic.denied && topic.author.isEmpty() && topic.authorId == null

private fun withoutServerNotices(list: TopicList): TopicList {
  val topics = list.topics.filterNot(::isServerNoticeRow)
  return if (topics.size == list.topics.size) list else list.copy(topics = topics)
}

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

@Serializable
data class BoardSearchItem(val board: Board, val parentName: String? = null)

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

private const val MAX_SAFE_INTEGER = 9007199254740991L

fun parseUserSearchInput(text: String): UserSearchQuery? {
  val trimmed = text.jsTrim()
  if (trimmed.isEmpty()) return null
  if (ALL_DIGITS.matches(trimmed)) {
    val uid = trimmed.toLongOrNull()
    if (uid != null && uid > 0 && uid <= MAX_SAFE_INTEGER) return UserSearchQuery.Uid(uid)
  }
  return UserSearchQuery.Username(trimmed)
}
