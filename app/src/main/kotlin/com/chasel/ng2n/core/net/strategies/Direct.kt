package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.StrategyOutcome

const val DIRECT_STRATEGY_NAME = "direct"

class DirectStrategy : FetchStrategy {

  override val name: String = DIRECT_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome =
    runAttempt(
      request = request,
      context = context,
      via = DIRECT_STRATEGY_NAME,
      combo = FetchCombo(
        format = request.format ?: ResponseFormat.JSON,
        host = request.host ?: context.host,
      ),
    )
}
