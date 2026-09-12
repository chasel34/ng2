package com.chasel.ng2n.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 信封解包。直译 `src/core/net/envelope.ts`。
 *
 * 顶层结构是 `{"data": {...}, "error": {...}, "time": N}`,`data` 与 `error` 互斥
 * (API 文档 §0.6)。`data` 内部大量用字符串数字键当数组,自动映射全部失效,
 * 只能由 `core/api` 手工遍历——所以这里到 [JsonElement] 为止,不再往下猜结构。
 */
data class NgaEnvelope(
  /** 顶层对象原样 */
  val root: JsonObject,
  /**
   * 顶层 `data`;少数不套壳的接口(app_api 版块分类树)里 `data === root`。
   *
   * **Kotlin 的 `null` = TS 的 `undefined`**(「只有 error 的响应」),
   * 服务端真的下发 `"data": null` 时这里是 `JsonNull` —— 比 TS 那边分得更清。
   */
  val data: JsonElement?,
  val time: Long? = null,
  /** 命中假错误白名单时保留,调用方需自行判 [data] 是否为空 */
  val fakeError: NgaServerError? = null,
)

/**
 * 信封形状。
 *
 * - [WRAPPED](默认):顶层是 `{"data":…}` 或 `{"error":…}`,绝大多数接口都是这样;
 * - [BARE]:顶层本身就是数据,没有 `data` 壳。
 *
 * **默认必须是 [WRAPPED]**(2026-08-13,「版块全空」排查):以前是「既没有 data 也没有
 * error 就把顶层当 data」,于是**任何**一个陌生的 JSON 对象(别的接口的响应、镜像域名的
 * 落地页、没验证过的格式档吐出来的东西)都会变成一份「合法但没有任何业务字段」的数据,
 * 一路走到 UI 变成「这个版块还没有主题」,还会被反封锁链当成「这个组合是好的」记进缓存。
 * 需要 bare 的接口自己声明(票 07 的 `NgaRequest.envelope`)。
 */
enum class EnvelopeShape { WRAPPED, BARE }

/**
 * 信封层的 JSON 解析器,**必须是严格模式**。
 *
 * 端点层解 `@Serializable` 数据类用的是宽容档(`core/api/NgaJson.kt`),两者不是一回事:
 * `isLenient` 会把 `<html>你被封了</html>` 这种整页 HTML 当成一个「不带引号的字符串」收下,
 * 于是「洗不成 JSON ⇒ 大概率被封 ⇒ 换下一个组合」这条信号当场消失
 * (`envelope/not-json`、`envelope/capture-thread-list-414-broken-bytes` 两条金样本
 * 锁的就是它)。这里要的正是 `JSON.parse` 的挑剔。
 */
private val EnvelopeJson = Json {
  isLenient = false
  ignoreUnknownKeys = false
  allowSpecialFloatingPointValues = false
}

/**
 * 清洗 → 解析 → 错误判定。
 *
 * 解析失败抛 `kind = PARSE` 的 [NgaError](可重试:解析失败基本等价于被封);
 * 服务端真错误抛 `kind = SERVER`(不重试);假错误白名单里的错误当成功返回。
 */
fun parseNgaJson(
  text: String,
  via: String? = null,
  shape: EnvelopeShape = EnvelopeShape.WRAPPED,
): NgaEnvelope {
  val cleaned = sanitizeNgaJson(text)
  if (cleaned.isEmpty()) {
    throw NgaError(NgaErrorKind.PARSE, "响应为空", via = via)
  }

  val root: JsonElement = try {
    EnvelopeJson.parseToJsonElement(cleaned).also { requireJsonParseCompatibleRoot(it) }
  } catch (cause: Exception) {
    throw NgaError(
      NgaErrorKind.PARSE,
      "响应不是合法 JSON：${cleaned.take(PREVIEW_LENGTH)}",
      via = via,
      cause = cause,
    )
  }

  if (root !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "响应顶层不是对象", via = via)
  }

  val serverError = extractServerError(root)
  if (serverError != null && !isFakeError(serverError.message)) {
    throw NgaError(
      NgaErrorKind.SERVER,
      serverError.message,
      code = serverError.code,
      via = via,
    )
  }

  val time = timeOf(root["time"])
  val shelled = root.containsKey("data") || root.containsKey("error")
  if (!shelled && shape == EnvelopeShape.WRAPPED) {
    throw NgaError(
      NgaErrorKind.PARSE,
      "响应顶层既没有 data 也没有 error：${cleaned.take(PREVIEW_LENGTH)}",
      via = via,
    )
  }

  return NgaEnvelope(
    root = root,
    // 只有 error 的响应 data 必须是「没有」,否则假错误会被当成有数据;
    // 顶层当 data 只在调用方显式声明 bare 时才发生
    data = if (shelled) root["data"] else root,
    time = time,
    fakeError = serverError,
  )
}

/** 错误信息里带的响应片段长度,与 TS 的 `cleaned.slice(0, 120)` 一致(按 UTF-16 码元数)。 */
private const val PREVIEW_LENGTH = 120

/** JSON 的数字字面量(RFC 8259)。`+1`、`.5`、`01`、`1.` 都不是。 */
private val JSON_NUMBER = Regex("""^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$""")

/**
 * `Json.parseToJsonElement` **比 `JSON.parse` 松**:哪怕关掉 `isLenient`,
 * 顶层的一串没有分隔符的裸字节(`<html>你被封了</html>`)也会被当成一个不带引号的原语收下,
 * 于是「洗不成 JSON ⇒ 大概率被封 ⇒ 换下一个组合」这条信号被降级成「顶层不是对象」
 * ——两条错误信息不同,更要紧的是**语义**不同(金样本 `envelope/not-json` 锁的就是它)。
 *
 * 这里把顶层原语按 JSON 的取值文法再核一遍:字符串、`true`/`false`/`null`、
 * 合法数字之外的裸原语一律当解析失败。结构体(对象/数组)不受影响。
 */
private fun requireJsonParseCompatibleRoot(root: JsonElement) {
  if (root !is JsonPrimitive || root.isString) return
  val content = root.content
  if (content == "true" || content == "false" || content == "null") return
  require(JSON_NUMBER.matches(content)) { "顶层不是合法的 JSON 取值:$content" }
}

/**
 * TS 是 `typeof root.time === 'number' ? root.time : undefined`。
 *
 * 这里收成 `Long`:全仓 `time` 都是秒级 unix 时间戳,给下游一个整数比给 `Double` 好用。
 * 服务端真发了小数会被截断——现实里没见过,记在这儿备查。
 */
private fun timeOf(raw: JsonElement?): Long? {
  if (raw !is JsonPrimitive || raw.isString) return null
  val value = raw.content.toDoubleOrNull() ?: return null
  return if (value.isFinite()) jsTrunc(value) else null
}
