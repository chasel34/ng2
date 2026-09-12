package com.chasel.ng2n.core.net

/**
 * 反封锁链的引擎(ADR-0002)。直译 `src/core/net/fetcher.ts` 的 `runStrategyChain`。
 */

/** 策略运行时能拿到的东西。**一次请求内不变**——每请求现读的设置在这里被定格。 */
class FetchContext(
  val transport: Transport,
  val host: String,
  val authMode: AuthMode,
  /**
   * 这次请求的身份,**在链开始前读一次就定死**(`CredentialSource.current()`)。
   *
   * 「定格」正是 P1-01 整改建议里的「写操作在用户触发时固定 UID/CID,整个生命周期不得切换」:
   * 链跑到一半用户切了号,在途的这一发仍归发起它的账号。
   */
  val credential: Credential?,
  /** 各 UA 档位的实际取值(webview 档由设备侧注入系统 UA)。 */
  val userAgents: UserAgents,
  /**
   * 重建一个新的传输层(ADR-0002 第 3 条:每次重试前重建)。
   * 不给就一直用 [transport] —— 单测与不做反封锁的场合不需要。
   */
  val renewTransport: (() -> Transport)? = null,
  /** 成功组合缓存(格式 × 域名),链上各档共用一份。 */
  val comboCache: ComboCache? = null,
  /** `read.php` 的 UA 档位覆盖(ADR-0002)。 */
  val readPhpUserAgent: UserAgentProfile? = null,
  /** Web 反解档位,每请求现读。 */
  val webFallbackMode: WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE,
  val onEvent: ((FetchEvent) -> Unit)? = null,
) {
  /** 换一个事件汇(引擎内部给策略套上 attempts 收集器时用)。 */
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

/** 一档策略的结果。不抛异常——一切失败都走这里,由引擎决定要不要往下走。 */
sealed interface StrategyOutcome {
  data class Ok(val result: NgaResult) : StrategyOutcome
  data class Failed(val error: NgaError) : StrategyOutcome
}

/**
 * 反封锁链(ADR-0002)的一环。
 *
 * 装配序:web-fallback(primary 档)→ format-rotation → switch-account →
 * web-fallback(secondary 默认档)→ topic-cache。
 */
interface FetchStrategy {
  val name: String
  suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome
}

sealed interface FetchEvent {
  data class StrategyStart(val strategy: String, val path: String) : FetchEvent
  data class StrategySuccess(val strategy: String, val path: String) : FetchEvent
  data class StrategyFailure(val strategy: String, val path: String, val error: NgaError) : FetchEvent

  /** 一次真正发出去的 HTTP 尝试。诊断日志就是由这些攒出来的。 */
  data class Attempt(
    val strategy: String,
    val path: String,
    val format: ResponseFormat,
    val host: String,
    val userAgent: UserAgentProfile,
    val userAgentValue: String,
    /** 这次用的账号 uid;游客为 null */
    val uid: String?,
    /** 成功时为 null */
    val error: NgaError? = null,
  ) : FetchEvent

  /** 整条链失败。[diagnostic] 已经带上全部尝试记录,可直接落盘。 */
  data class ChainFailure(val diagnostic: FetchDiagnostic) : FetchEvent

  /**
   * 整条链拿到了结果。`diagnostic.success` 带落点摘要(组合 / `data` 顶层键 / 条数)——
   * 「成功但空」这种静默降级只有在这里才看得见。
   */
  data class ChainSuccess(val diagnostic: FetchDiagnostic) : FetchEvent
}

/**
 * 诊断摘要里只留业务参数:`__inchst` / `__output` 这些是我们自己拼的,排障没用。
 *
 * 值原样带出去,**脱敏在 data 层做**(P1-04 的收口点是 `redactDiagnosticParams`)——
 * core 不知道哪些键敏感,那是一张会随业务变的白名单。
 */
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

/**
 * 按顺序跑策略链:
 * - 谁先成功用谁;
 * - 失败且 `retryable`(解析失败 / HTTP 状态错误 / 网络错误 ≈ 被封)→ 换下一档;
 * - 失败且不可重试(服务端语义错误)→ **立刻抛出**,不浪费后面的兜底。
 *
 * 链上每一次真正发出去的请求都被记下来,失败时攒成一条诊断挂到 [NgaError.diagnostic] 上
 * 并发 [FetchEvent.ChainFailure] —— 错误页要显示的「试过哪些组合」就是它。
 *
 * **成功也留一条记录**(ADR-0002 第 5 条):以前只有整条链失败才有诊断,于是
 * 「链自认为成功、拿回来的却是空数据」这种静默降级完全看不见 —— 真出过这个事故。
 */
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
        // `unavailable` 是「这一档不适用」(只有一个账号、缓存里没有这条),不是失败的原因。
        // 已经有更实质的错误时别让它盖过去——否则用户看到的是「没有可换的账号」
        // 而不是真正该说的「这一页被封了」。
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
