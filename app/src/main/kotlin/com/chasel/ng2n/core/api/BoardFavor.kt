package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.signedBoardId
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun favorQuery() = queryOf("__lib" to "forum_favor2", "__act" to "forum_favor")

private fun parseFavorBoard(raw: JsonElement?): Board? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null

  val fid = nonZero(signedBoardId(int(raw, "fid")))
  val stid = nonZero(int(raw, "stid"))
  val id = stid ?: fid ?: nonZero(signedBoardId(int(raw, "id"))) ?: return null

  return Board(
    id = id,
    kind = if (stid == null) BoardKind.BOARD else BoardKind.COLLECTION,
    fid = fid,
    stid = stid,
    name = name,
    info = str(raw, "info"),
  )
}

fun parseBoardFavorites(data: JsonElement?): List<Board> {
  if (data !is JsonObject) return emptyList()
  return orderedValues(data["0"]).mapNotNull(::parseFavorBoard)
}

suspend fun fetchBoardFavorites(client: NgaClient): List<Board> {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = favorQuery(),
      form = queryOf("action" to "get"),
    ),
  )
  return parseBoardFavorites(result.data)
}

private suspend fun writeFavor(client: NgaClient, action: String, boardId: Long) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = favorQuery(),
      form = queryOf("action" to action, "fid" to boardId),
    ),
  )
}

suspend fun addBoardFavorite(client: NgaClient, boardId: Long) {
  try {
    writeFavor(client, "add", boardId)
  } catch (error: NgaError) {
    if (error.kind == NgaErrorKind.SERVER && error.text.contains("已经收藏")) return
    throw error
  }
}

suspend fun removeBoardFavorite(client: NgaClient, boardId: Long) {
  writeFavor(client, "del", boardId)
}

suspend fun clearBoardFavorites(client: NgaClient): List<Board> {
  val boards = fetchBoardFavorites(client)
  for (board in boards) removeBoardFavorite(client, board.id)
  return boards
}

private val BOARD_ID_INPUT = Regex("^-?\\d+$")

fun parseBoardIdInput(text: String): Long? {
  val trimmed = text.jsTrim()
  if (!BOARD_ID_INPUT.matches(trimmed)) return null
  val id = trimmed.toLongOrNull() ?: return null
  return if (id != 0L && id in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) id else null
}

private const val MAX_SAFE_INTEGER = 9007199254740991L
