package com.chasel.ng2n.core.ai

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.math.BigDecimal

@Serializable
data class AiPrice(val version: String = AI_PRICE_VERSION, val verified: String = "2026-09-14",
  val hit: Long = 40, val miss: Long = 2000, val output: Long = 8000) {
  // 价格单位为每百万 token 的千分之一人民币；费用向上取整到微元。
  fun cost(input: Long, completion: Long, cached: Long = 0): Long {
    require(input >= 0 && completion >= 0 && cached in 0..input)
    return (cached * hit + (input - cached) * miss + completion * output + 999) / 1000
  }
  companion object {
    fun at(time: Long, certain: Boolean = false): AiPrice {
      val utc = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
      val peak = utc.dayOfWeek.value <= 5 && (utc.hour in 1..3 || utc.hour in 6..9)
      return if (!certain || peak) AiPrice() else AiPrice(hit = 20, miss = 1000, output = 4000)
    }
  }
}
const val AI_PRICE_VERSION = "deepseek-flash-cny-2026-09-14"
const val AI_CURRENCY = "CNY"
// 早期账本以美元记账且序列化时省略默认字段：旧记录的默认价会被读成人民币价表，只有非默认的谷时价保留美元数值。
// 官方人民币价与美元价的比例固定为 20:3，按此换算金额，已结算的请求按 token 用人民币价表重算。
fun AiBudgetBook.convertLegacyUsd(): AiBudgetBook {
  if (currency == AI_CURRENCY) return this
  fun cny(micros: Long): Long = (micros * 20 + 2) / 3
  return copy(
    currency = AI_CURRENCY,
    analyses = analyses.map { it.copy(limit = cny(it.limit)) },
    requests = requests.map { request ->
      val usdScale = request.price.miss < AiPrice().miss / 2
      val price = if (usdScale) request.price.copy(hit = cny(request.price.hit), miss = cny(request.price.miss), output = cny(request.price.output)) else request.price
      request.copy(price = price.copy(version = AI_PRICE_VERSION), reserved = cny(request.reserved),
        cost = request.cost?.let { if (it == 0L) 0L else price.cost(request.input, request.output, request.cached) })
    },
  )
}
fun aiMoney(micros: Long): String = "¥" + BigDecimal.valueOf(micros, 6).setScale(3, java.math.RoundingMode.UP).toPlainString()
fun aiDay(time: Long): String = Instant.ofEpochMilli(time).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toString()

val AI_WEB_TOOLS = listOf("search_web", "read_webpage")

// 协议字段、系统提示与工具结果外壳的固定余量。
const val AI_PROTOCOL_TOKENS = 4096L
// 读图前解码采样到长边不超过约 2048 像素；按每 16×16 像素一个视觉 token 的保守切片估算得到每张 16384，
// 仍高于常见视觉模型的实测值，真实用量待样本校准。
const val AI_IMAGE_TOKENS = 16_384L

// DeepSeek 官方口径约为 1 个中文字符 0.6 token、1 个英文字符 0.3 token。
// 按 UTF-8 字节数估算会把中文高估约 5 倍，短问答档第一次请求就会超额；这里按字符取略高于官方的系数。
fun estimateTokens(text: String): Long {
  var wide = 0L
  var narrow = 0L
  for (ch in text) if (ch.code >= 0x2E80) wide++ else narrow++
  return (wide * 7 + narrow * 4 + 9) / 10
}

// 执行上限是资源保护：靠工具执行次数与连续无进展阈值收敛，迭代上限只作兜底，
// 必须容得下「技能读取 + 多页论坛读取 + 读图 + 联网核查 + 收尾」。
// outputTokens 是回答正文的预算，thinkingTokens 是同一次生成里思考的预算：两者分别估算，
// 服务商的 max_tokens 和计费的 completion 同时覆盖它们，因此按 maxTokens 下发与预留。
@Serializable
data class AiRunLimits(val iterations: Int = 40, val toolCalls: Int = 24, val repeats: Int = 4,
  val outputTokens: Int = 2048, val thinkingTokens: Int = 2048) {
  val maxTokens: Int get() = outputTokens + thinkingTokens
}

// 达到本次运行的执行上限：已读取资料与已生成文字保留，与真正被打断的运行区分。
class AiRunLimitReached(val detail: String) : IllegalStateException(detail)

fun aiRunLimitDetail(error: Throwable): String? =
  generateSequence(error) { it.cause }.take(8).filterIsInstance<AiRunLimitReached>().firstOrNull()?.detail

@Serializable
data class AiBudgetAnalysis(val id: String, val conversation: String, val limit: Long, val stopped: Boolean = false)
@Serializable
data class AiBudgetRequest(val id: String, val analysis: String, val conversation: String, val day: String,
  val reserved: Long, val price: AiPrice, val status: String = "reserved", val input: Long = 0,
  val output: Long = 0, val cached: Long = 0, val cost: Long? = null, val images: Int = 0,
  val retry: Boolean = false, val web: Int = 0) {
  val charged: Long get() = cost ?: reserved
}
@Serializable
data class AiBudgetBook(val analyses: List<AiBudgetAnalysis> = emptyList(), val requests: List<AiBudgetRequest> = emptyList(),
  val currency: String = "USD") {
  fun reserve(request: AiBudgetRequest, dailyLimit: Long?): AiBudgetBook {
    if (requests.any { it.id == request.id }) return this
    val analysis = analyses.single { it.id == request.analysis }
    if (analysis.stopped || requests.filter { it.analysis == analysis.id }.sumOf { it.charged } + request.reserved > analysis.limit) throw AiBudgetExceeded(false)
    // 未结的跨日请求仍占用当前每日可用额度，同时保留原归属日。
    val daily = requests.filter { it.day == request.day || it.cost == null }.sumOf { it.charged }
    if (dailyLimit != null && daily + request.reserved > dailyLimit) throw AiBudgetExceeded(true)
    return copy(requests = requests + request)
  }
  fun settle(id: String, input: Long, output: Long, cached: Long = 0): AiBudgetBook = copy(requests = requests.map {
    if (it.id != id || it.cost != null) it else it.copy(status = "settled", input = input, output = output,
      cached = cached, cost = it.price.cost(input, output, cached))
  })
  fun sending(id: String): AiBudgetBook = copy(requests = requests.map {
    if (it.id == id && it.status == "reserved") it.copy(status = "in_flight") else it
  })
  fun releaseUnsent(id: String): AiBudgetBook = copy(requests = requests.map {
    if (it.id == id && it.cost == null) it.copy(status = "not_sent", cost = 0) else it
  })
  // 服务端明确拒绝的请求没有 usage，释放预留但保留请求记录。
  fun rejected(id: String): AiBudgetBook = copy(requests = requests.map {
    if (it.id == id && it.cost == null) it.copy(status = "rejected", cost = 0) else it
  })
  fun pending(id: String? = null): AiBudgetBook = copy(requests = requests.map {
    if (it.cost == null && (id == null || it.id == id)) it.copy(status = "pending_verification") else it
  })
  // 网页次数随发起这批工具调用的请求保存，设置页的今日用量才能与对话页脚同口径。
  fun webRead(id: String): AiBudgetBook = copy(requests = requests.map {
    if (it.id == id) it.copy(web = it.web + 1) else it
  })
}
class AiBudgetExceeded(val daily: Boolean) : IllegalStateException(if (daily) "已达到今日额度" else "已达到本次额度")

enum class AiFailure(val title: String, val retryable: Boolean = false, val settings: Boolean = false) {
  AUTH("API Key 无效或余额不足", settings = true), BALANCE("余额不足", settings = true), PARAMETERS("请求参数或模型能力不兼容"),
  RATE("服务限流，请稍后重试", true), SERVICE("模型服务暂时不可用", true),
  UNKNOWN("请求结果不明"), INTERRUPTED("回答生成中断"), OFFLINE("网络连接失败"), LIMIT("达到限制")
}
// 429 以外的 4xx 是服务端在处理前明确拒绝，没有 usage，也不重试。
fun rejectedHttpStatus(message: String): Int? =
  Regex("HTTP (4\\d\\d)\\b").find(message)?.groupValues?.get(1)?.toInt()?.takeIf { it != 429 }

fun classifyAiFailure(error: Throwable, outputStarted: Boolean): AiFailure {
  // 达到执行上限不是被打断：本轮已读资料与已生成文字仍然有效，用户可以继续。
  if (generateSequence(error) { it.cause }.take(8).any { it is AiRunLimitReached }) return AiFailure.LIMIT
  if (outputStarted) return AiFailure.INTERRUPTED
  val message = generateSequence(error) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }
  return when {
    Regex("\\b401\\b").containsMatchIn(message) -> AiFailure.AUTH
    Regex("\\b402\\b").containsMatchIn(message) -> AiFailure.BALANCE
    Regex("\\b429\\b").containsMatchIn(message) -> AiFailure.RATE
    Regex("\\b(500|503)\\b").containsMatchIn(message) -> AiFailure.SERVICE
    Regex("\\b(400|422)\\b").containsMatchIn(message) -> AiFailure.PARAMETERS
    rejectedHttpStatus(message) != null -> AiFailure.PARAMETERS
    generateSequence(error) { it.cause }.take(8).any { it is java.net.UnknownHostException || it is java.net.ConnectException } -> AiFailure.OFFLINE
    else -> AiFailure.UNKNOWN
  }
}
