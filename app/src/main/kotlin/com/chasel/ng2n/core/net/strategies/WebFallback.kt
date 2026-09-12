package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.StrategyOutcome
import com.chasel.ng2n.core.net.WebFallbackMode
import com.chasel.ng2n.core.net.interfaceKeyOf
import com.chasel.ng2n.core.net.web.parseReadPageHtml

const val WEB_FALLBACK_STRATEGY_NAME = "web-fallback"

private const val SUPPORTED_PATH = "read.php"

interface WebReadParser {

  val available: Boolean

  fun parse(text: String, via: String): NgaEnvelope
}

object ReadHtmlWebParser : WebReadParser {

  override val available: Boolean = true

  override fun parse(text: String, via: String): NgaEnvelope = parseReadPageHtml(text, via)
}

object UnavailableWebReadParser : WebReadParser {

  override val available: Boolean = false

  override fun parse(text: String, via: String): NgaEnvelope =
    throw NgaError(NgaErrorKind.UNAVAILABLE, "网页反解器没接上", via = via)
}

class WebFallbackStrategy(
  private val placement: Placement,
  private val parser: WebReadParser = ReadHtmlWebParser,
) : FetchStrategy {

  enum class Placement { PRIMARY, SECONDARY }

  override val name: String = WEB_FALLBACK_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    if (!request.path.startsWith(SUPPORTED_PATH)) {
      return unavailable("Web 反解只支持 $SUPPORTED_PATH,这条是 ${request.path}")
    }

    val mode = context.webFallbackMode
    if (!activeAt(mode)) {
      return unavailable("Web 反解档位是 ${mode.wire},${placement.name.lowercase()} 位置不启用")
    }
    if (!parser.available) {
      return unavailable("网页反解器没接上,这一档让位")
    }

    val combo = FetchCombo(
      format = ResponseFormat.HTML,
      host = request.host
        ?: context.comboCache?.get(interfaceKeyOf(request))?.host
        ?: context.host,
    )
    val outcome = runAttempt(
      request = request,
      context = context,
      via = WEB_FALLBACK_STRATEGY_NAME,
      combo = combo,
      transport = context.renewTransport?.invoke(),
      parse = { text -> parser.parse(text, WEB_FALLBACK_STRATEGY_NAME) },
    )

    if (outcome is StrategyOutcome.Ok) return outcome
    val error = (outcome as StrategyOutcome.Failed).error
    if (mode != WebFallbackMode.ONLY || !error.retryable) return outcome
    return StrategyOutcome.Failed(
      NgaError(
        kind = error.kind,
        message = error.text,
        status = error.status,
        via = WEB_FALLBACK_STRATEGY_NAME,
        cause = error,
        retryable = false,
      ),
    )
  }

  private fun activeAt(mode: WebFallbackMode): Boolean = when (placement) {
    Placement.PRIMARY -> mode == WebFallbackMode.PRIMARY || mode == WebFallbackMode.ONLY
    Placement.SECONDARY -> mode == WebFallbackMode.SECONDARY
  }

  private fun unavailable(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = WEB_FALLBACK_STRATEGY_NAME),
  )
}
