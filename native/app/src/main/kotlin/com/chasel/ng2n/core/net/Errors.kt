package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 错误模型。直译 `src/core/net/errors.ts`(白名单在 TS 侧住 `constants.ts`,
 * 这里跟着用它们的判据走——票 06 建 `Constants.kt` 时别再抄一份)。
 */

/** 错误从**哪一层**解析出来的。注意:这与「要不要重试」不是一回事,见 ADR-0002 第 7 条。 */
enum class NgaErrorKind(
  /** 金样本 / 诊断日志里的字面量,与 TS 的联合类型成员一一对应。 */
  val wire: String,
) {
  /** 传输层失败:DNS、超时、连接断 */
  NETWORK("network"),

  /** HTTP 状态错误,且 body 里没有可用的错误信息 */
  HTTP("http"),

  /** 响应洗不成合法 JSON / 结构不对——基本等价于被封(ADR-0002) */
  PARSE("parse"),

  /** 服务端明确返回了 error 对象 */
  SERVER("server"),

  /** 策略不适用(例如缓存里没有这条),仅用于策略链内部流转 */
  UNAVAILABLE("unavailable"),
}

/**
 * 除了服务端语义错误,其余失败都值得换下一档策略试试。
 * 服务端明确说「找不到主题」这类语义错误换几次策略也还是这个结果,直接抛给调用方。
 *
 * 比 MNGA 的判据(只有解析失败/HTTP 状态错误才重试)多放了 [NgaErrorKind.NETWORK]:
 * 本项目的链末端是帖子缓存与网页兜底,断网时正该落到缓存那一档,
 * 而不是在第一档就把错误抛给用户。调用方主动取消的请求会显式传 `retryable = false`。
 */
private fun defaultRetryable(kind: NgaErrorKind): Boolean = kind != NgaErrorKind.SERVER

/**
 * core 层统一的失败。
 *
 * `code` 用 [JsonPrimitive] 而不是 `String`/`Int`:TS 那边是 `string | number`,
 * 而金样本要求 `"?"`(字符串)与 `403`(数字)可分辨——收成 String 会把 `403` 变成 `"403"`。
 *
 * 反封锁链跑完之后 `runStrategyChain` 要往错误上补一份诊断摘要(错误页拿它渲染),
 * 那个字段等票 06 落地 `FetchDiagnostic` 时再加,**加的时候必须是可写的**:重新包一个
 * NgaError 会丢掉调用方用 `is` / 引用比较建立的那些判断。
 */
class NgaError(
  val kind: NgaErrorKind,
  message: String,
  /** 服务端错误码;JSON 侧取 `error.code`,缺失时为 `"?"` */
  val code: JsonPrimitive? = null,
  val status: Int? = null,
  /** 触发本次失败的策略名 */
  val via: String? = null,
  cause: Throwable? = null,
  /** 不给就按 [kind] 推导 */
  retryable: Boolean? = null,
) : Exception(message, cause) {

  val retryable: Boolean = retryable ?: defaultRetryable(kind)

  /** [Exception.message] 在 Kotlin 里是可空的,但本类构造时必给,所以这里收窄。 */
  val text: String get() = message ?: ""

  override fun toString(): String =
    "NgaError(kind=${kind.wire}, retryable=$retryable, message=$text)"
}

/** 服务端 error 对象抽出来的结构。 */
data class NgaServerError(val code: JsonPrimitive, val message: String)

/** `error.code` 缺失或不是 string/number 时的占位。 */
val UNKNOWN_SERVER_CODE: JsonPrimitive = JsonPrimitive("?")

/**
 * 「假错误」白名单(API 文档 §0.7):出现这些词的 error 视为成功。
 * 注意 `找不到用户` 也在里面,调用方拿到 `fakeError` 后要另判 data 是否为空。
 */
val FAKE_ERROR_MESSAGES: List<String> = listOf(
  "完毕",
  "没找到",
  "没有符合条件的结果",
  "今天已经签到",
  "找不到用户",
)

/**
 * 「身份没带上」白名单:这些服务端错误说的不是「你要的这条数据有问题」,
 * 而是「这一发请求没被认出身份」——换一个格式 × 域名组合往往就好了(ADR-0002 第 6 条)。
 *
 * 为什么和 host 强相关:okhttp 的 cookie jar 是**按域名**存的,只要 jar 里对该域名有
 * cookie,我们自己拼的 `Cookie` 头就整条被顶掉,同一个凭证在 A 域名失效、在 B 域名照常能用。
 *
 * 只有「手上确实有凭证」时才按这个白名单放行**换域名**(那道闸在 format-rotation,票 06):
 * 游客本来就没登录,让他把所有组合白跑一遍只会把错误页拖慢十几秒。但**标可重试这一步
 * 对游客也做**——链上后面还有网页兜底与帖子缓存,别把整条链掐死。
 */
val AUTH_LEVEL_SERVER_MESSAGES: List<String> = listOf("未登录")

/**
 * 命中「假错误」白名单的(API 文档 §0.7)视为成功。
 * 白名单里的词是**子串匹配**:真实响应里会出现「发贴完毕」「操作完毕」这类前缀。
 */
fun isFakeError(message: String): Boolean = FAKE_ERROR_MESSAGES.any { message.contains(it) }

/**
 * 这条服务端错误是不是「这一发没带上身份」而不是语义错误(API 文档 §0.7 之外的经验规则)。
 * 命中的话值得换个组合再试一次,判据与出处见 [AUTH_LEVEL_SERVER_MESSAGES]。
 */
fun isAuthLevelServerError(message: String): Boolean =
  AUTH_LEVEL_SERVER_MESSAGES.any { message.contains(it) }

/**
 * 从顶层响应对象里抽 JSON 错误(API 文档 §0.7):
 * `{"error":{"0":"信息"}}` 或 `{"error":{"code":403,"0":"信息"}}`。没有错误时返回 null。
 *
 * 说明文字在这儿就剥成纯文本([stripServerHtml]):NGA 的错误说明是给网页版 innerHTML 用的,
 * 带 `<br/>` 与 `<a>`;抽取口只有这一个,剥在这里错误页、诊断日志、各处提示就都拿到人话,
 * 不必各自记得剥一遍。
 */
fun extractServerError(root: JsonElement?): NgaServerError? {
  // TS 的 `isRecord` 按约定把数组与 null 排除在外
  if (root !is JsonObject) return null
  val error = root["error"] ?: return null
  if (error is JsonNull) return null

  if (error is JsonPrimitive && error.isString) {
    // 剥完只剩空的(整条说明全是标签)仍然算错误——空说明比误判成功强
    if (error.content.isEmpty()) return null
    val message = stripServerHtml(error.content)
    return NgaServerError(
      UNKNOWN_SERVER_CODE,
      message.ifEmpty { unknownMessage(UNKNOWN_SERVER_CODE) },
    )
  }

  // 数组形态(`{"error":["访问速度过快"]}`):`isRecord` 把数组排除在外,
  // 不单独认一下的话服务端说的原话会被整条丢掉,用户看到的是「响应里没有 data」
  // 这种毫无信息量的话(2026-08-13,「版块全空」排查)
  if (error is JsonArray) {
    val messages = error
      .filter { it is JsonPrimitive && it.isString }
      .map { stripServerHtml((it as JsonPrimitive).content) }
      .filter { it.isNotEmpty() }
    // 空数组不当错误:PHP 的空数组序列化出来就是 `[]`,和「这个字段没内容」分不开,
    // 而对象形态那一档(有 error 对象但没有可读信息)仍然算错误——那是明确的结构
    return if (messages.isEmpty()) null else NgaServerError(UNKNOWN_SERVER_CODE, messages.joinToString("；"))
  }

  if (error !is JsonObject) return null

  val code = serverCodeOf(error["code"])

  val messages = mutableListOf<String>()
  for ((key, value) in jsOwnEntries(error)) {
    if (key == "code") continue
    if (value !is JsonPrimitive || !value.isString) continue
    val message = stripServerHtml(value.content)
    if (message.isNotEmpty()) messages += message
  }
  // 有 error 对象但没有可读信息,仍然算错误
  if (messages.isEmpty()) return NgaServerError(code, unknownMessage(code))
  return NgaServerError(code, messages.joinToString("；"))
}

/** TS 的 `typeof rawCode === 'string' || typeof rawCode === 'number'`,布尔与 null 不算。 */
private fun serverCodeOf(raw: JsonElement?): JsonPrimitive {
  if (raw !is JsonPrimitive || raw is JsonNull) return UNKNOWN_SERVER_CODE
  if (raw.isString) return raw
  return if (raw.content.toDoubleOrNull() != null) raw else UNKNOWN_SERVER_CODE
}

private fun unknownMessage(code: JsonPrimitive): String = "未知错误（code=${code.content}）"
