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

/**
 * 版块收藏(CONTEXT.md「版块收藏」——云端收藏的版块列表,与主题收藏夹无关)。
 * 直译 `src/core/api/board-favor.ts`。
 *
 * 接口族只有一个端点(API 文档 §1.3,2026-08-08 真实抓包):
 *
 * ```
 * POST nuke.php?__lib=forum_favor2&__act=forum_favor
 * form: action=get                    → 列表,版块数组在 data["0"];空收藏时 data 是 {}
 * form: action=add|del, fid=<id>      → 增删,成功时 data["0"] 是文本「操作成功」
 * ```
 *
 * 实测要点:
 * - **合集也走 `fid` 参数**:把 stid 当 fid 传,服务端自己识别(传 31576766 后
 *   列表条目带的是 `stid`),不存在的 id 报「合集不存在」。
 * - 列表条目形如 `{id, fid?|stid?, name, info?}`,fid 与 stid 互斥;新收藏的排在最前。
 * - `del` 天然幂等(删未收藏的照样「操作成功」);`add` 重复收藏报错
 *   「你已经收藏了这个版面」,这里吞掉当成功,让上层的乐观切换不怕竞态。
 * - 需要登录:游客请求报「你必须先登录论坛」(server 错误),UI 层应先挡住入口。
 */

private fun favorQuery() = queryOf("__lib" to "forum_favor2", "__act" to "forum_favor")

private fun parseFavorBoard(raw: JsonElement?): Board? {
  if (raw !is JsonObject) return null
  val name = str(raw, "name") ?: return null

  // 与分类树同一套规则(BoardTree.kt):0 不是有效 id,stid 优先于 fid;
  // 版块 id 可以是负数,过一道符号还原(core/local 的 signedBoardId)
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

/**
 * 解析 `action=get` 的 `data`。版块数组在 `data["0"]`;一个都没收藏时
 * `data` 是 `{}`(连 `"0"` 键都没有),返回空数组而不是报错。
 */
fun parseBoardFavorites(data: JsonElement?): List<Board> {
  if (data !is JsonObject) return emptyList()
  return orderedValues(data["0"]).mapNotNull(::parseFavorBoard)
}

/** 拉云端收藏列表,服务端顺序(新收藏在前)原样保留。 */
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
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
      operation = Operation.WRITE,
      query = favorQuery(),
      // 合集也传 fid(见文件头注释);服务端语义错误由 envelope 抛 NgaError(kind = SERVER)
      form = queryOf("action" to action, "fid" to boardId),
    ),
  )
}

/**
 * 收藏一个版块(合集传 stid,一样走 `fid` 参数)。
 * 「已经收藏」不算失败:乐观切换后重试/多端并发时,结果与意图一致。
 */
suspend fun addBoardFavorite(client: NgaClient, boardId: Long) {
  try {
    writeFavor(client, "add", boardId)
  } catch (error: NgaError) {
    if (error.kind == NgaErrorKind.SERVER && error.text.contains("已经收藏")) return
    throw error
  }
}

/** 取消收藏。服务端本身幂等,删未收藏的也返回成功。 */
suspend fun removeBoardFavorite(client: NgaClient, boardId: Long) {
  writeFavor(client, "del", boardId)
}

/**
 * 清空收藏:服务端没有批量接口,只能先拉列表再逐个删。
 * 串行而不是并发——收藏一般就十来个,不值得为它冒被风控的险(ADR-0002 的克制原则)。
 * 返回删掉的列表,给「撤销」重新收藏用。
 */
suspend fun clearBoardFavorites(client: NgaClient): List<Board> {
  val boards = fetchBoardFavorites(client)
  for (board in boards) removeBoardFavorite(client, board.id)
  return boards
}

/** 十进制整数(fid 可以是负数,如网事杂谈 -7)。 */
private val BOARD_ID_INPUT = Regex("^-?\\d+$")

/**
 * 「添加版面 ID」对话框的输入解析。
 * 输入到底是 fid 还是 stid 由服务端定夺——add 时统一传 `fid` 参数,
 * 之后重拉列表,条目带 `stid` 就是合集(stid 优先,CONTEXT.md「合集」)。
 */
fun parseBoardIdInput(text: String): Long? {
  val trimmed = text.jsTrim()
  if (!BOARD_ID_INPUT.matches(trimmed)) return null
  // 越过 Long 的输入(TS 那边是 `Number.isSafeInteger` 挡掉)一律不认
  val id = trimmed.toLongOrNull() ?: return null
  return if (id != 0L && id in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) id else null
}

/** JS 的 `Number.MAX_SAFE_INTEGER`:超过它 TS 侧的 `Number.isSafeInteger` 就不认了。 */
private const val MAX_SAFE_INTEGER = 9007199254740991L
