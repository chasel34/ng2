package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.DEFAULT_MAX_ATTEMPTS
import com.chasel.ng2n.core.net.DEFAULT_ROTATION_FORMATS
import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NGA_HOSTS
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.StrategyOutcome
import com.chasel.ng2n.core.net.Transport
import com.chasel.ng2n.core.net.enumerateCombos
import com.chasel.ng2n.core.net.interfaceKeyOf
import com.chasel.ng2n.core.net.isAuthLevelServerError

const val FORMAT_ROTATION_STRATEGY_NAME = "format-rotation"

class FormatRotationStrategy(
  private val formats: List<ResponseFormat> = DEFAULT_ROTATION_FORMATS,
  private val hosts: List<String> = NGA_HOSTS,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : FetchStrategy {

  override val name: String = FORMAT_ROTATION_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    if (request.isWrite) {
      return unavailableOutcome("写操作不进格式轮换(P1-01):重发非幂等请求会重复提交")
    }

    val key = interfaceKeyOf(request)
    val cache = context.comboCache
    val rotation = request.formats ?: formats
    val requested: FetchCombo? =
      if (request.format == null && request.host == null) {
        null
      } else {
        FetchCombo(
          format = request.format ?: rotation.firstOrNull() ?: ResponseFormat.JSON,
          host = request.host ?: context.host,
        )
      }
    val preferred = cache?.get(key)
    val combos = enumerateCombos(
      formats = rotation,
      hosts = listOf(context.host) + hosts,
      maxAttempts = maxAttempts,
      requested = requested,
      preferred = preferred,
    )

    var transport: Transport = context.transport
    var lastError: NgaError? = null
    for ((index, combo) in combos.withIndex()) {
      val renew = context.renewTransport
      if (index > 0 && renew != null) transport = renew()

      val outcome = runAttempt(
        request = request,
        context = context,
        via = FORMAT_ROTATION_STRATEGY_NAME,
        combo = combo,
        transport = transport,
      )
      if (outcome is StrategyOutcome.Ok) {
        cache?.remember(key, combo)
        return outcome
      }
      val error = (outcome as StrategyOutcome.Failed).error
      if (combo == preferred) cache?.forget(key)
      lastError = error
      if (context.credential == null && isAuthLevelServerError(error.text)) {
        return outcome
      }
      if (!error.retryable) {
        if (error.kind == NgaErrorKind.SERVER) cache?.remember(key, combo)
        return outcome
      }
    }

    cache?.forget(key)
    return StrategyOutcome.Failed(
      lastError ?: NgaError(
        NgaErrorKind.UNAVAILABLE,
        "没有可尝试的格式 × 域名组合",
        via = FORMAT_ROTATION_STRATEGY_NAME,
      ),
    )
  }

  private fun unavailableOutcome(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = FORMAT_ROTATION_STRATEGY_NAME),
  )
}
