package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.AccountPolicy
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialOverride
import com.chasel.ng2n.core.net.DEFAULT_ROTATION_FORMATS
import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.StrategyOutcome
import com.chasel.ng2n.core.net.interfaceKeyOf

const val SWITCH_ACCOUNT_STRATEGY_NAME = "switch-account"

fun nextCredentialAfter(accounts: List<Credential>, current: Credential?): Credential? {
  if (accounts.size < 2) return null
  val index = if (current == null) -1 else accounts.indexOfFirst { it.uid == current.uid }
  return accounts.getOrNull((index + 1) % accounts.size)
}

class SwitchAccountStrategy(
  private val listCredentials: suspend () -> List<Credential>,
) : FetchStrategy {

  override val name: String = SWITCH_ACCOUNT_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    // 写请求的重试不能把操作落到另一个账号。
    if (request.isWrite || request.accountPolicy == AccountPolicy.PINNED) {
      return unavailable("这条请求的身份已钉死(P1-01),不换账号重试")
    }

    val current = request.credential?.credential ?: context.credential
    val next = nextCredentialAfter(listCredentials(), current)
      ?: return unavailable("只有一个已登录账号,没有可换的")

    val cached = context.comboCache?.get(interfaceKeyOf(request))
    val combo = cached ?: FetchCombo(
      format = request.format ?: DEFAULT_ROTATION_FORMATS.firstOrNull() ?: ResponseFormat.JSON,
      host = request.host ?: context.host,
    )
    return runAttempt(
      request = request,
      context = context,
      via = SWITCH_ACCOUNT_STRATEGY_NAME,
      combo = combo,
      credentialOverride = CredentialOverride(next),
    )
  }

  private fun unavailable(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = SWITCH_ACCOUNT_STRATEGY_NAME),
  )
}
