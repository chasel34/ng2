package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.FetchAttemptLog
import com.chasel.ng2n.core.net.FetchDiagnostic
import com.chasel.ng2n.core.net.FetchOutcomeSummary
import com.chasel.ng2n.data.diagnostics.AttemptError
import com.chasel.ng2n.data.diagnostics.AttemptLog
import com.chasel.ng2n.data.diagnostics.DiagnosticRecord
import com.chasel.ng2n.data.diagnostics.OutcomeSummary

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
