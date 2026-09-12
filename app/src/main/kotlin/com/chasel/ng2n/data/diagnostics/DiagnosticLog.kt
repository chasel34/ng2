package com.chasel.ng2n.data.diagnostics

import java.time.Instant

val SAFE_DIAGNOSTIC_PARAMS: Set<String> = setOf(
  "tid",
  "fid",
  "stid",
  "pid",
  "page",
  "lite",
  "v2",
  "opt",
  "order_by",
  "recommend",
  "authorid",
)

const val REDACTED_PLACEHOLDER = "<redacted>"

fun redactDiagnosticParams(params: Map<String, String>): Map<String, String> =
  params.entries
    .filterNot { it.key.startsWith("__") }
    .associate { (key, value) ->
      key to if (key.lowercase() in SAFE_DIAGNOSTIC_PARAMS) value else REDACTED_PLACEHOLDER
    }

data class AttemptError(
  val kind: String,
  val message: String,
  val status: Int? = null,
)

data class AttemptLog(
  val strategy: String,
  val format: String,
  val host: String,
  val userAgent: String,
  val uid: String? = null,
  val error: AttemptError? = null,
)

data class OutcomeSummary(
  val strategy: String,
  val format: String,
  val host: String,
  val keys: List<String>,
  val rows: Int? = null,
)

data class DiagnosticRecord(
  val at: Long,
  val path: String,
  val params: Map<String, String> = emptyMap(),
  val message: String,
  val attempts: List<AttemptLog> = emptyList(),
  val success: OutcomeSummary? = null,
)

private const val SUMMARY_PARAM_LIMIT = 3

fun diagnosticSummary(record: DiagnosticRecord): String {
  val parts = redactDiagnosticParams(record.params).entries
    .take(SUMMARY_PARAM_LIMIT)
    .map { "${it.key}=${it.value}" }
    .toMutableList()
  record.attempts.lastOrNull()?.let { parts += "ua=${it.userAgent}" }
  return parts.joinToString(" · ")
}

private fun formatAttempt(attempt: AttemptLog, index: Int): String {
  val combo = "${attempt.format} @ ${attempt.host}"
  val who = if (attempt.uid == null) "游客" else "uid=${attempt.uid}"
  val error = attempt.error
  val result = if (error == null) {
    "ok"
  } else {
    "${error.kind}${if (error.status == null) "" else " ${error.status}"}: ${error.message}"
  }
  return "  ${index + 1}. [${attempt.strategy}] $combo ua=${attempt.userAgent} $who → $result"
}

fun formatOutcome(success: OutcomeSummary): String {
  val rows = if (success.rows == null) "" else " ${success.rows} 条"
  val keys = if (success.keys.isEmpty()) "(无字段)" else success.keys.joinToString(",")
  return "[${success.strategy}] ${success.format} @ ${success.host} → data{$keys}$rows"
}

fun formatDiagnostic(record: DiagnosticRecord): String {
  val query = redactDiagnosticParams(record.params).entries
    .joinToString("&") { "${it.key}=${it.value}" }
  val target = if (query == "") record.path else "${record.path}?$query"
  val verdict = if (record.success == null) {
    "失败:${record.message}"
  } else {
    "成功:${formatOutcome(record.success)}"
  }
  val head = "${Instant.ofEpochMilli(record.at)} $target $verdict"
  return (listOf(head) + record.attempts.mapIndexed { index, a -> formatAttempt(a, index) })
    .joinToString("\n")
}

const val DIAGNOSTIC_LOG_LIMIT = 50

fun appendDiagnosticLog(
  log: List<String>,
  record: DiagnosticRecord,
  limit: Int = DIAGNOSTIC_LOG_LIMIT,
): List<String> {
  val next = log + formatDiagnostic(record)
  return if (next.size <= limit) next else next.takeLast(limit)
}
