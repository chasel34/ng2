package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.SwitchAccountStrategy
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.core.net.strategies.TopicCacheStrategy
import com.chasel.ng2n.core.net.strategies.WebFallbackStrategy
import com.chasel.ng2n.core.net.strategies.WebReadParser
import com.chasel.ng2n.core.net.strategies.UnavailableWebReadParser

class NgaClient(
  private val transports: TransportFactory,
  private val credentials: CredentialSource,
  private val settings: NetworkSettingsSource,
  private val userAgents: UserAgents,
  val comboCache: ComboCache = InMemoryComboCache(),
  private val readChain: List<FetchStrategy>,
  private val writeChain: List<FetchStrategy> = listOf(DirectStrategy()),
  private val authMode: AuthMode = AuthMode.BOTH,
  private val onDiagnostic: ((FetchDiagnostic) -> Unit)? = null,
  private val onEvent: ((FetchEvent) -> Unit)? = null,
) {

  suspend fun execute(request: NgaRequest): NgaResult {
    val chain = if (request.isWrite) writeChain else readChain
    val context = FetchContext(
      transport = transports.create(),
      host = request.host ?: settings.host(),
      authMode = request.auth ?: authMode,
      credential = request.credential?.credential ?: credentials.current(),
      userAgents = userAgents,
      renewTransport = transports::renew,
      comboCache = comboCache,
      readPhpUserAgent = settings.readPhpUserAgent(),
      webFallbackMode = settings.webFallbackMode(),
      onEvent = { event ->
        when (event) {
          is FetchEvent.ChainFailure -> onDiagnostic?.invoke(event.diagnostic)
          is FetchEvent.ChainSuccess -> onDiagnostic?.invoke(event.diagnostic)
          else -> Unit
        }
        onEvent?.invoke(event)
      },
    )
    return runStrategyChain(chain, request, context)
  }

  fun forgetSuccessfulCombo(interfaceKey: String) = comboCache.forget(interfaceKey)

  fun successfulCombos(): List<Pair<String, ComboRecord>> = comboCache.entries()

  companion object {

    fun defaultReadChain(
      listCredentials: suspend () -> List<Credential>,
      webReadParser: WebReadParser = UnavailableWebReadParser,
      topicCacheReader: TopicCacheReader? = null,
    ): List<FetchStrategy> = buildList {
      add(WebFallbackStrategy(WebFallbackStrategy.Placement.PRIMARY, webReadParser))
      add(FormatRotationStrategy())
      add(SwitchAccountStrategy(listCredentials))
      add(WebFallbackStrategy(WebFallbackStrategy.Placement.SECONDARY, webReadParser))
      if (topicCacheReader != null) add(TopicCacheStrategy(topicCacheReader))
    }
  }
}
