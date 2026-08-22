package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 反封锁链的诊断记录(ADR-0002 第 5 条:**可观测性是这条链的一部分**)。
 * 直译 `src/core/net/diagnostics.ts` 的数据部分。
 *
 * ## 为什么这里再定义一遍(而不是复用 `data/diagnostics/DiagnosticLog.kt`)
 *
 * 那边是票 14 的**落盘与脱敏**层(`DiagnosticRecord` / `AttemptLog`,带 P1-04 的白名单),
 * 住在 `data`。分层铁律是 data → core 单向依赖,core 不能反过来 import data,
 * 所以链自己需要一份**纯 core 的值类型**。两边形状一致,映射在
 * `data/net/ChainDiagnostics.kt` 一处完成(约 20 行),不散落。
 *
 * ## 记什么、不记什么
 *
 * 只记**结构信息**:哪个组合、UA 档位、uid、`data` 顶层有哪些键、列表几条。
 * **绝不含 cid 与正文**(ADR-0002 第 5 条)。业务参数原样带出去,由 data 层按
 * `SAFE_DIAGNOSTIC_PARAMS` 白名单脱敏后才落盘(P1-04 的收口点在那边,不在这里)。
 */

/** 一次尝试失败的原因,摊平成纯数据(对应 [NgaError] 的 kind / message / status)。 */
data class FetchAttemptError(
  val kind: String,
  val message: String,
  val status: Int? = null,
)

/** 链上一次实际发出的 HTTP 尝试。成功的那次 [error] 为 null。 */
data class FetchAttemptLog(
  val strategy: String,
  /** 格式参数档位,如 `json`(`__output=8`) */
  val format: String,
  val host: String,
  /** UA 档位名(`webview` / `windowsPhone` …),完整 UA 串在 [userAgentValue] */
  val userAgent: String,
  val userAgentValue: String,
  /** 这次尝试用的账号 uid;游客为 null */
  val uid: String? = null,
  val error: FetchAttemptError? = null,
)

/**
 * 成功那一次的落点摘要(ADR-0002 第 5 条,2026-08-13「版块全空」排查)。
 *
 * 以前只有整条链失败才留记录,于是**「链自认为成功、但拿回来的是一份空数据」
 * 这种静默降级完全不可观测**——线上那次「所有版块都空」正是这种。
 */
data class FetchOutcomeSummary(
  /** 产出结果的策略名 */
  val strategy: String,
  /** 格式档位名,如 `json`(`__output=8`) */
  val format: String,
  val host: String,
  /** `data` 顶层的键,超出上限就截断 */
  val keys: List<String>,
  /** 列表类接口的条数(`__T` / `__R` 的元素个数),不是列表就没有 */
  val rows: Int? = null,
)

data class FetchDiagnostic(
  /** 记录时刻(ms since epoch) */
  val at: Long,
  val path: String,
  /** 业务参数(tid/page/…),已归一化成字符串,`__` 开头的框架参数不计 */
  val params: Map<String, String> = emptyMap(),
  /** 最终抛给调用方的错误说明;成功记录里是落点的一句话 */
  val message: String,
  val attempts: List<FetchAttemptLog> = emptyList(),
  /** 有它就是一条**成功**记录;没有就是整条链失败 */
  val success: FetchOutcomeSummary? = null,
)

/** 摘要里最多列几个 `data` 顶层键。 */
private const val SUMMARY_KEY_LIMIT = 8

/**
 * 从一次成功的响应里抠出可记录的结构信息。
 * 只看键名与条数——正文一律不进日志(这份日志是要导出发给别人看的)。
 *
 * `__T` / `__R` **对象与数组两种形态都要认**(ADR-0002 第 10 条):`__output=11` 的 `__T`
 * 是货真价实的 JSON 数组,只认对象的话条数会静默变成「没有」。
 */
fun summarizeEnvelopeData(data: JsonElement?): FetchDataSummary {
  val record = data as? JsonObject ?: return FetchDataSummary(emptyList(), null)
  val keys = record.keys.take(SUMMARY_KEY_LIMIT)
  for (listKey in listOf("__T", "__R")) {
    when (val list = record[listKey]) {
      is JsonObject -> return FetchDataSummary(keys, list.size)
      is JsonArray -> return FetchDataSummary(keys, list.size)
      else -> Unit
    }
  }
  return FetchDataSummary(keys, null)
}

/** [summarizeEnvelopeData] 的产物。 */
data class FetchDataSummary(val keys: List<String>, val rows: Int?)

/** 成功记录的落点那一行:用了哪个组合、拿回来什么形状。 */
fun formatOutcome(success: FetchOutcomeSummary): String {
  val rows = if (success.rows == null) "" else " ${success.rows} 条"
  val keys = if (success.keys.isEmpty()) "(无字段)" else success.keys.joinToString(",")
  return "[${success.strategy}] ${success.format} @ ${success.host} → data{$keys}$rows"
}

/** 尝试记录里「没发出去过请求」时的占位(TS 侧同款字面量)。 */
const val NO_ATTEMPT_PLACEHOLDER = "(未发请求)"

// ---------------------------------------------------------------------------
// 「加载失败」页上那两行说明(直译 `src/core/net/diagnostics.ts` 的 describeFetchFailure)
// ---------------------------------------------------------------------------

/** 「加载失败」页上那两行说明。 */
data class FetchFailureCopy(
  /** 服务端到底返回了什么。普通字重的那半句(`服务端返回` / `连不上服务器`) */
  val headline: String,
  /**
   * 状态码那一截(`HTTP 403`)。设计稿只把它加粗并换等宽字,前面那半句是正常正文——
   * 所以拆成两个字段,别再让页面去猜从哪儿断开。没有状态码的档为 null。
   */
  val code: String? = null,
  /** 为什么会这样、还能怎么办 */
  val hint: String,
)

/**
 * 把链上的失败翻成人话(设计稿 isError:`服务端返回 HTTP 403 Forbidden` +
 * `通常是客户端 UA 被拒或需要重新登录`)。
 *
 * **关键是别把 `error.message` 直接摆到屏幕上** —— 反封锁链末端抛的是
 * `fetch failed: java.io.IOException: …` 这种给开发者看的东西(RN 侧 M3 验收缺陷 3)。
 */
fun describeFetchFailure(kind: String, status: Int?, message: String): FetchFailureCopy {
  val code = status?.let { "HTTP $it" }
  return when (kind) {
    // 服务端把话说清楚了(权限不足、找不到主题…),照搬比我们编强
    NgaErrorKind.SERVER.wire ->
      FetchFailureCopy(message, null, "这是论坛给出的说明,换个页面或重新登录再试")

    NgaErrorKind.HTTP.wire -> FetchFailureCopy(
      headline = if (code == null) "服务端没有返回内容" else "服务端返回",
      code = code,
      hint = "通常是客户端 UA 被拒或需要重新登录",
    )

    NgaErrorKind.PARSE.wire -> FetchFailureCopy(
      headline = if (code == null) "响应内容解析不了" else "服务端返回",
      code = code,
      hint = "第三方客户端被拦是最常见的原因,可以先用网页版打开",
    )

    NgaErrorKind.NETWORK.wire -> FetchFailureCopy("连不上服务器", null, "检查网络连接后重试")

    else -> FetchFailureCopy("这一页没有可用的加载方式", null, "所有兜底都试过了,可以用网页版打开")
  }
}

/** 从任意异常翻成两行说明:不是 [NgaError] 的(渲染期异常、别的库抛的)也要有话说。 */
fun describeFetchFailure(error: Throwable?): FetchFailureCopy = when (error) {
  is NgaError -> describeFetchFailure(error.kind.wire, error.status, error.text)
  else -> describeFetchFailure("unknown", null, error?.message ?: "这一页拉不下来")
}
