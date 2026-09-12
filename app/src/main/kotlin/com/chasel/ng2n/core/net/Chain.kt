package com.chasel.ng2n.core.net

class FetchContext(
  val transport: Transport,
  val host: String,
  val authMode: AuthMode,
  val credential: Credential?,
  val userAgents: UserAgents,
  val renewTransport: (() -> Transport)? = null,
  val comboCache: ComboCache? = null,
  val readPhpUserAgent: UserAgentProfile? = null,
  val webFallbackMode: WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE,
  val onEvent: ((FetchEvent) -> Unit)? = null,
) {
  fun withEvents(sink: (FetchEvent) -> Unit): FetchContext = FetchContext(
    transport = transport,
    host = host,
    authMode = authMode,
    credential = credential,
    userAgents = userAgents,
    renewTransport = renewTransport,
    comboCache = comboCache,
    readPhpUserAgent = readPhpUserAgent,
    webFallbackMode = webFallbackMode,
    onEvent = sink,
  )
}

sealed interface StrategyOutcome {
  data class Ok(val result: NgaResult) : StrategyOutcome
  data class Failed(val error: NgaError) : StrategyOutcome
}

interface FetchStrategy {
  val name: String
  suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome
}

sealed interface FetchEvent {
  data class StrategyStart(val strategy: String, val path: String) : FetchEvent
  data class StrategySuccess(val strategy: String, val path: String) : FetchEvent
  data class StrategyFailure(val strategy: String, val path: String, val error: NgaError) : FetchEvent

  data class Attempt(
    val strategy: String,
    val path: String,
    val format: ResponseFormat,
    val host: String,
    val userAgent: UserAgentProfile,
    val userAgentValue: String,
    val uid: String?,
    val error: NgaError? = null,
  ) : FetchEvent

  data class ChainFailure(val diagnostic: FetchDiagnostic) : FetchEvent

  data class ChainSuccess(val diagnostic: FetchDiagnostic) : FetchEvent
}

internal fun diagnosticParams(query: QueryParams): Map<String, String> {
  val params = LinkedHashMap<String, String>()
  for ((key, value) in query) {
    if (key.startsWith("__")) continue
    val text = when (value) {
      null -> null
      is QueryValue.Flag -> if (value.value) "1" else null
      is QueryValue.Num -> value.value.toString()
      is QueryValue.Gbk -> value.value.ifEmpty { null }
      is QueryValue.Text -> value.value.ifEmpty { null }
    } ?: continue
    params[key] = text
  }
  return params
}

private fun FetchEvent.Attempt.toLog(): FetchAttemptLog = FetchAttemptLog(
  strategy = strategy,
  format = format.wire,
  host = host,
  userAgent = userAgent.wire,
  userAgentValue = userAgentValue,
  uid = uid,
  error = error?.let {
    FetchAttemptError(kind = it.kind.wire, message = it.text, status = it.status)
  },
)

suspend fun runStrategyChain(
  strategies: List<FetchStrategy>,
  request: NgaRequest,
  context: FetchContext,
  now: () -> Long = System::currentTimeMillis,
): NgaResult {
  val attempts = ArrayList<FetchAttemptLog>()
  val outer = context.onEvent
  val onEvent: (FetchEvent) -> Unit = { event ->
    if (event is FetchEvent.Attempt) attempts.add(event.toLog())
    outer?.invoke(event)
  }
  val scoped = context.withEvents(onEvent)
  val params = diagnosticParams(request.query)

  fun fail(error: NgaError): NgaError {
    val diagnostic = FetchDiagnostic(
      at = now(),
      path = request.path,
      params = params,
      message = error.text,
      attempts = attempts.toList(),
    )
    error.diagnostic = diagnostic
    onEvent(FetchEvent.ChainFailure(diagnostic))
    return error
  }

  fun succeed(result: NgaResult): NgaResult {
    val last = attempts.lastOrNull()
    val summary = summarizeEnvelopeData(result.data)
    val success = FetchOutcomeSummary(
      strategy = result.via,
      format = last?.format ?: NO_ATTEMPT_PLACEHOLDER,
      host = last?.host ?: NO_ATTEMPT_PLACEHOLDER,
      keys = summary.keys,
      rows = summary.rows,
    )
    onEvent(
      FetchEvent.ChainSuccess(
        FetchDiagnostic(
          at = now(),
          path = request.path,
          params = params,
          message = formatOutcome(success),
          attempts = attempts.toList(),
          success = success,
        ),
      ),
    )
    return result
  }

  if (strategies.isEmpty()) {
    throw fail(NgaError(NgaErrorKind.UNAVAILABLE, "没有可用的请求策略"))
  }

  var lastError: NgaError? = null
  for (strategy in strategies) {
    onEvent(FetchEvent.StrategyStart(strategy.name, request.path))
    when (val outcome = strategy.run(request, scoped)) {
      is StrategyOutcome.Ok -> {
        onEvent(FetchEvent.StrategySuccess(strategy.name, request.path))
        return succeed(outcome.result)
      }

      is StrategyOutcome.Failed -> {
        onEvent(FetchEvent.StrategyFailure(strategy.name, request.path, outcome.error))
        if (
          outcome.error.kind != NgaErrorKind.UNAVAILABLE ||
          lastError == null ||
          lastError.kind == NgaErrorKind.UNAVAILABLE
        ) {
          lastError = outcome.error
        }
        if (!outcome.error.retryable) throw fail(outcome.error)
      }
    }
  }
  throw fail(lastError ?: NgaError(NgaErrorKind.UNAVAILABLE, "所有策略都没有产出结果"))
}
