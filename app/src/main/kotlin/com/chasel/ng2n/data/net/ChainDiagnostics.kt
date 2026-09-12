package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.FetchAttemptLog
import com.chasel.ng2n.core.net.FetchDiagnostic
import com.chasel.ng2n.core.net.FetchOutcomeSummary
import com.chasel.ng2n.data.diagnostics.AttemptError
import com.chasel.ng2n.data.diagnostics.AttemptLog
import com.chasel.ng2n.data.diagnostics.DiagnosticRecord
import com.chasel.ng2n.data.diagnostics.OutcomeSummary

/**
 * 链的诊断(core 的纯值类型)→ 落盘形态(票 14 的 `data/diagnostics`)。
 *
 * 两边形状一样但住在不同层:core 不能 import data(分层是单向的),
 * 所以映射收在这一处,而不是让某一边将就另一边。
 *
 * **脱敏不在这里做**:`formatDiagnostic` / `diagnosticSummary` 会按
 * `SAFE_DIAGNOSTIC_PARAMS` 白名单挡下非结构性参数(P1-04 的收口点)。
 * 这里只搬运 —— `userAgentValue`(完整 UA 串)**故意不搬**:
 * 落盘那一行只放得下档位名,完整串对排障没有增量信息。
 * `cid` 从来不在这条路径上出现过(链只记 uid)。
 */
fun FetchDiagnostic.toRecord(): DiagnosticRecord = DiagnosticRecord(
  at = at,
  path = path,
  params = params,
  message = message,
  attempts = attempts.map { it.toLog() },
  success = success?.toSummary(),
)

private fun FetchAttemptLog.toLog(): AttemptLog = AttemptLog(
  strategy = strategy,
  format = format,
  host = host,
  userAgent = userAgent,
  uid = uid,
  error = error?.let { AttemptError(kind = it.kind, message = it.message, status = it.status) },
)

private fun FetchOutcomeSummary.toSummary(): OutcomeSummary = OutcomeSummary(
  strategy = strategy,
  format = format,
  host = host,
  keys = keys,
  rows = rows,
)
