package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class FetchAttemptError(
  val kind: String,
  val message: String,
  val status: Int? = null,
)

data class FetchAttemptLog(
  val strategy: String,
  val format: String,
  val host: String,
  val userAgent: String,
  val userAgentValue: String,
  val uid: String? = null,
  val error: FetchAttemptError? = null,
)

data class FetchOutcomeSummary(
  val strategy: String,
  val format: String,
  val host: String,
  val keys: List<String>,
  val rows: Int? = null,
)

data class FetchDiagnostic(
  val at: Long,
  val path: String,
  val params: Map<String, String> = emptyMap(),
  val message: String,
  val attempts: List<FetchAttemptLog> = emptyList(),
  val success: FetchOutcomeSummary? = null,
)

private const val SUMMARY_KEY_LIMIT = 8

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

data class FetchDataSummary(val keys: List<String>, val rows: Int?)

fun formatOutcome(success: FetchOutcomeSummary): String {
  val rows = if (success.rows == null) "" else " ${success.rows} 条"
  val keys = if (success.keys.isEmpty()) "(无字段)" else success.keys.joinToString(",")
  return "[${success.strategy}] ${success.format} @ ${success.host} → data{$keys}$rows"
}

const val NO_ATTEMPT_PLACEHOLDER = "(未发请求)"

data class FetchFailureCopy(
  val headline: String,
  val code: String? = null,
  val hint: String,
)

fun describeFetchFailure(kind: String, status: Int?, message: String): FetchFailureCopy {
  val code = status?.let { "HTTP $it" }
  return when (kind) {
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

fun describeFetchFailure(error: Throwable?): FetchFailureCopy = when (error) {
  is NgaError -> describeFetchFailure(error.kind.wire, error.status, error.text)
  else -> describeFetchFailure("unknown", null, error?.message ?: "这一页拉不下来")
}
