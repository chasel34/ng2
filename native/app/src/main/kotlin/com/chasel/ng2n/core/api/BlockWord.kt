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

/**
 * 官方屏蔽词(API 文档 §11.5,云同步,与 NGA 网页版「控制面板 → 屏蔽」同一份数据)。
 * 直译 `src/core/api/block-word.ts`。
 *
 * ```
 * POST nuke.php?__lib=ucp&__act=get_block_word&uid=<uid>     # 读
 * POST nuke.php?__lib=ucp&__act=set_block_word&data=<D>      # 写
 * Referer: <host>/nuke.php?func=ucp&uid=<uid>                ← 必须带
 * ```
 *
 * 三个容易踩的点:
 *
 * 1. **读回来的不是结构化数据**,是 `data["0"]` 里的一段多行纯文本:
 *    第 1 行是格式版本(实测恒为 `1`),第 2 行是空格分隔的关键词,
 *    第 3 行是空格分隔的 `uid/用户名` 对。行数不够(从没设置过)要当空表,别报错。
 * 2. **写是整表覆盖**,没有增删接口。所以增删都得「先读回来 → 改内存里的表 → 整表写回」,
 *    调用方拿到的 [BlockWordList] 就是那张表。
 * 3. **`data` 走 GBK**(`gbk()` 标记,query 层负责按 GBK percent-encode)。
 *    用 UTF-8 写上去,网页版那边看到的就是乱码——「与网页版互通」全靠这一步。
 *
 * 分隔符是空格,所以**关键词与用户名里不能有空白**;[blockWordError] 在写之前拦掉。
 */

/** 官方屏蔽表里的一个人。老数据可能只有名字没有 uid。 */
@Serializable
data class BlockedUser(val uid: Long? = null, val name: String)

/** 一整张官方屏蔽表(关键词 + 用户),读写都是它。 */
@Serializable
data class BlockWordList(
  val words: List<String> = emptyList(),
  val users: List<BlockedUser> = emptyList(),
)

val EMPTY_BLOCK_WORDS = BlockWordList()

/** 写入串的第一行,实测恒为 `1`(格式版本)。 */
private const val BLOCK_WORD_VERSION = "1"

/** JS 的 `Number.MAX_SAFE_INTEGER`。 */
private const val MAX_SAFE_INTEGER = 9007199254740991L

private val LINE_BREAK = Regex("\r\n|[\r\n]")
private val WHITESPACE_RUN = Regex("\\s+")

/** 这个接口的 Referer 要带 uid(API 文档 §11.5)。 */
fun blockWordRefererPath(uid: String): String = "$UCP_REFERER_PATH&uid=$uid"

private fun splitTokens(line: String?): List<String> {
  if (line == null) return emptyList()
  return line.split(WHITESPACE_RUN).filter { it.isNotEmpty() }
}

/** `12345/张三` → `BlockedUser(12345, "张三")`;名字里可能有 `/`,只切第一个。 */
private fun parseBlockedUser(token: String): BlockedUser {
  val slash = token.indexOf('/')
  if (slash < 0) return BlockedUser(name = token)

  val parsed = jsNumber(token.substring(0, slash))
  val uid = if (parsed.isFinite()) jsTrunc(parsed) else 0L
  val name = token.substring(slash + 1)
  // `Number.isSafeInteger(uid) && uid > 0` 之外的一律当「只有名字」
  if (parsed != uid.toDouble() || uid <= 0 || uid > MAX_SAFE_INTEGER) {
    return BlockedUser(name = name.ifEmpty { token })
  }
  return BlockedUser(uid = uid, name = name.ifEmpty { uid.toString() })
}

private fun formatBlockedUser(user: BlockedUser): String =
  if (user.uid == null) user.name else "${user.uid}/${user.name}"

/**
 * 解 `get_block_word` 的 `data`。从没设置过屏蔽词时服务端可能只回一行甚至空串,
 * 一律折成空表——「没有屏蔽词」不是错误。
 */
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

/** 拼 `set_block_word` 的 `data` 原文(GBK 编码由 query 层做)。 */
fun serializeBlockWords(list: BlockWordList): String = listOf(
  BLOCK_WORD_VERSION,
  list.words.joinToString(" "),
  list.users.joinToString(" ", transform = ::formatBlockedUser),
).joinToString("\r\n")

/**
 * 校验一条待写入的关键词/用户名,返回对话框就地显示的错误文案。
 * 空格是表里的分隔符,带空白的词写上去会被服务端拆成两条。
 */
fun blockWordError(text: String, label: String = "关键词"): String? {
  val trimmed = text.jsTrim()
  if (trimmed.isEmpty()) return "请输入要屏蔽的$label"
  if (WHITESPACE_RUN.containsMatchIn(trimmed)) return "${label}中间不能有空格（官方屏蔽表用空格分隔）"
  return null
}

/**
 * 把云端这张表折成匹配器认的 [FilterRule],好让列表与楼层只跑一次 `matchFilterRules`。
 *
 * **官方关键词一律按普通子串匹配**(`regex = false`):这份表是空格分隔的一行文本,
 * 网页版那边到底有没有把某条当正则跑,我们无从判断;按子串走最多是少屏蔽几条,
 * 按正则走则可能把 `^` `[` 这类字符当语法误伤一大片。真机与网页版对拍后再调整。
 */
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

/** 拉云端屏蔽表。[uid] 是当前账号,读取参数与 Referer 都要用。 */
suspend fun fetchBlockWords(client: NgaClient, uid: String): BlockWordList {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "ucp", "__act" to "get_block_word", "uid" to uid),
      refererPath = blockWordRefererPath(uid),
    ),
  )
  return parseBlockWords(result.data)
}

/**
 * 整表写回(不是增量)。`data` 标了 `gbk()`——**这一步错了就与网页版对不上**:
 * query 层看到 GBK 参数会按 GBK percent-encode,并撤掉 `__inchst=UTF8`
 * (否则服务端按 UTF-8 解这串字节,存进去的是乱码)。
 */
suspend fun setBlockWords(client: NgaClient, uid: String, list: BlockWordList) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
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
