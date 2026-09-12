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

/**
 * 反封锁链前半段的主力:**格式参数 × 域名**的组合枚举(ADR-0002 / API 文档 §0.8)。
 * 直译 `src/core/net/strategies/format-rotation.ts`。
 *
 * - 只有解析错误 / HTTP 状态错误 / 网络错误才换下一个组合;服务端语义错误
 *   (权限不足、找不到主题)说明这个组合根本没被封,立刻交回链上抛给调用方。
 * - 成功(含「成功地拿到语义错误」)的组合按接口 key 记进缓存,下次这个接口优先用它开局。
 * - **每次重试前重建 HTTP client**(ADR-0002 第 3 条):连接可能已经被中间设备盯上。
 *   原生这一档是真的换连接,RN 上是空操作。
 *
 * @param formats 参与轮换的格式档位,默认 JSON 家族三档(见 `Combo.kt` 为什么不含 XML)。
 * @param hosts 参与轮换的域名,默认全部官方域名(API 文档 §0.1)。
 * @param maxAttempts 组合数上限,默认 [DEFAULT_MAX_ATTEMPTS]。
 */
class FormatRotationStrategy(
  private val formats: List<ResponseFormat> = DEFAULT_ROTATION_FORMATS,
  private val hosts: List<String> = NGA_HOSTS,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : FetchStrategy {

  override val name: String = FORMAT_ROTATION_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    // ── 修 P1-01:写操作禁入这一档 ────────────────────────────────────────────
    // 装配上写请求走的是只有 direct 的那条链,本来就到不了这里;这道闸是**第二层保险**:
    // 万一以后有人把写请求塞进读链(或调用方自己拼了一条链),也不会重放非幂等操作。
    // 服务端可能已经执行了、只是响应丢在路上,换个格式重发 = 重复收藏夹 / 点赞翻回去。
    if (request.isWrite) {
      return unavailableOutcome("写操作不进格式轮换(P1-01):重发非幂等请求会重复提交")
    }

    val key = interfaceKeyOf(request)
    val cache = context.comboCache
    val rotation = request.formats ?: formats
    // 调用方点名了格式或域名就把那个组合排在轮换前面,但不独占——它照样可能被封,被封了还是要换。
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
      // 默认域名(设置页可改)永远排在官方域名表前面
      hosts = listOf(context.host) + hosts,
      maxAttempts = maxAttempts,
      requested = requested,
      preferred = preferred,
    )

    var transport: Transport = context.transport
    var lastError: NgaError? = null
    for ((index, combo) in combos.withIndex()) {
      // 重试前重建 HTTP client(第一次直接用链上现成的那个)
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
      // 缓存里那个组合当场失手就先摘掉:并发的同接口请求不该再从它开局,
      // 而这一轮后面的组合成功了自然会把新的写回去(ADR-0002 第 2 条的「自愈」)
      if (combo == preferred) cache?.forget(key)
      lastError = error
      // 游客态的「未登录」:换域名救不了(哪个域名都没有 cookie),别白跑一整轮组合。
      // 但错误本身仍然是可重试的,链上后面的网页兜底 / 帖子缓存还该拿到机会(ADR-0002 第 6 条)。
      if (context.credential == null && isAuthLevelServerError(error.text)) {
        return outcome
      }
      if (!error.retryable) {
        // 能解析出服务端语义错误 = 这个组合是通的,值得记住。
        // (丢身份的那种「未登录」在 classifyHttpResponse 里已被标成可重试,走不到这儿——
        //  正是为了避开这条缓存规则,否则丢身份的域名会被钉住一个缓存周期)
        if (error.kind == NgaErrorKind.SERVER) cache?.remember(key, combo)
        return outcome
      }
    }

    // 全组合失败:缓存里那个也不灵了,清掉免得下次还从它开局
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
