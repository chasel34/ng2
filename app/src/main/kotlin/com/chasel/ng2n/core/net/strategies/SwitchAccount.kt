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

/**
 * 当前凭证之后的下一个账号(循环取)。直译 `nextCredentialsAfter`。
 *
 * 只有一个账号(或一个都没有)时返回 null —— 没得换,这一档就不该启用。
 * 当前凭证不在表里(刚退出登录之类)时从头一个开始。
 */
fun nextCredentialAfter(accounts: List<Credential>, current: Credential?): Credential? {
  if (accounts.size < 2) return null
  val index = if (current == null) -1 else accounts.indexOfFirst { it.uid == current.uid }
  return accounts.getOrNull((index + 1) % accounts.size)
}

/**
 * 反封锁链的换账号重试档(ADR-0002;Android v4 的对策)。
 * 直译 `src/core/net/strategies/switch-account.ts`。
 *
 * 取下一个已登录账号的 cookie **只试一次**,成了就成了,不成交给链上后面的兜底。
 *
 * 只在多账号时启用:单账号换来换去还是同一个 cookie,白等一次往返。
 * 用哪个组合不再枚举——前一档 format-rotation 已经把组合空间跑完了,
 * 这一档变的是**身份**,所以沿用缓存里的组合(没有就用默认档)。
 *
 * @param listCredentials 已登录账号的凭证表,顺序即账号管理页的顺序。
 *   注入而不是直接读仓库:core 层零 Android 依赖,凭证存在 Keystore 里,
 *   拿到这一层的只能是纯数据(`data/account/AccountStore` 提供实现)。
 */
class SwitchAccountStrategy(
  private val listCredentials: suspend () -> List<Credential>,
) : FetchStrategy {

  override val name: String = SWITCH_ACCOUNT_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    // ── 修 P1-01:写操作 / 钉死身份的请求禁入这一档 ──────────────────────────
    // 换账号重发一个非幂等写 = 把写入落到**别人**头上(审计里的「跨账号写入」)。
    // 装配上写请求走的是只有 direct 的那条链,这道闸是第二层保险。
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
