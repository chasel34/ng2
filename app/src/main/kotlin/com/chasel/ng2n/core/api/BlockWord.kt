package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.FilterRuleOrigin
import com.chasel.ng2n.core.local.filterRuleId
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.gbk
import com.chasel.ng2n.core.net.jsNumber
import com.chasel.ng2n.core.net.jsTrim
import com.chasel.ng2n.core.net.jsTrunc
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class BlockedUser(val uid: Long? = null, val name: String)

@Serializable
data class BlockWordList(
  val words: List<String> = emptyList(),
  val users: List<BlockedUser> = emptyList(),
)

val EMPTY_BLOCK_WORDS = BlockWordList()

private const val BLOCK_WORD_VERSION = "1"

private const val MAX_SAFE_INTEGER = 9007199254740991L

private val LINE_BREAK = Regex("\r\n|[\r\n]")
private val WHITESPACE_RUN = Regex("\\s+")

fun blockWordRefererPath(uid: String): String = "$UCP_REFERER_PATH&uid=$uid"

private fun splitTokens(line: String?): List<String> {
  if (line == null) return emptyList()
  return line.split(WHITESPACE_RUN).filter { it.isNotEmpty() }
}

private fun parseBlockedUser(token: String): BlockedUser {
  val slash = token.indexOf('/')
  if (slash < 0) return BlockedUser(name = token)

  val parsed = jsNumber(token.substring(0, slash))
  val uid = if (parsed.isFinite()) jsTrunc(parsed) else 0L
  val name = token.substring(slash + 1)
  if (parsed != uid.toDouble() || uid <= 0 || uid > MAX_SAFE_INTEGER) {
    return BlockedUser(name = name.ifEmpty { token })
  }
  return BlockedUser(uid = uid, name = name.ifEmpty { uid.toString() })
}

private fun formatBlockedUser(user: BlockedUser): String =
  if (user.uid == null) user.name else "${user.uid}/${user.name}"

fun parseBlockWords(data: JsonElement?): BlockWordList {
  val raw = when {
    data is JsonPrimitive && data.isString -> data.content
    data is JsonObject -> (data["0"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    else -> null
  } ?: return EMPTY_BLOCK_WORDS

  val lines = raw.split(LINE_BREAK)
  return BlockWordList(
    words = splitTokens(lines.getOrNull(1)),
    users = splitTokens(lines.getOrNull(2)).map(::parseBlockedUser),
  )
}

fun serializeBlockWords(list: BlockWordList): String = listOf(
  BLOCK_WORD_VERSION,
  list.words.joinToString(" "),
  list.users.joinToString(" ", transform = ::formatBlockedUser),
).joinToString("\r\n")

fun blockWordError(text: String, label: String = "关键词"): String? {
  val trimmed = text.jsTrim()
  if (trimmed.isEmpty()) return "请输入要屏蔽的$label"
  if (WHITESPACE_RUN.containsMatchIn(trimmed)) return "${label}中间不能有空格（官方屏蔽表用空格分隔）"
  return null
}

fun officialFilterRules(list: BlockWordList): List<FilterRule> {
  val users = list.users.map { user ->
    FilterRule(
      id = filterRuleId(
        FilterRuleOrigin.OFFICIAL,
        FilterRuleKind.USER,
        user.uid?.toString() ?: user.name,
      ),
      kind = FilterRuleKind.USER,
      origin = FilterRuleOrigin.OFFICIAL,
      value = user.name,
      regex = false,
      uid = user.uid,
    )
  }
  val words = list.words.map { word ->
    FilterRule(
      id = filterRuleId(FilterRuleOrigin.OFFICIAL, FilterRuleKind.KEYWORD, word),
      kind = FilterRuleKind.KEYWORD,
      origin = FilterRuleOrigin.OFFICIAL,
      value = word,
      regex = false,
    )
  }
  return users + words
}

suspend fun fetchBlockWords(client: NgaClient, uid: String): BlockWordList {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "ucp", "__act" to "get_block_word", "uid" to uid),
      refererPath = blockWordRefererPath(uid),
      validate = ::rejectNonUcpPayload,
    ),
  )
  return parseBlockWords(result.data)
}

suspend fun setBlockWords(client: NgaClient, uid: String, list: BlockWordList) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf(
        "__lib" to "ucp",
        "__act" to "set_block_word",
        "data" to gbk(serializeBlockWords(list)),
      ),
      refererPath = blockWordRefererPath(uid),
    ),
  )
}
