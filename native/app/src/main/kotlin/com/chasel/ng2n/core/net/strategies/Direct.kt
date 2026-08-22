package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchStrategy
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.ResponseFormat
import com.chasel.ng2n.core.net.StrategyOutcome

const val DIRECT_STRATEGY_NAME = "direct"

/**
 * 直连:按当前域名发**一次**请求。直译 `src/core/net/strategies/direct.ts`。
 *
 * 只发一次是它与 format-rotation 的全部区别:不轮换、不换账号、不碰缓存。
 *
 * **写操作的链就只有这一档**(修 P1-01):服务端可能已经执行了、只是响应丢在路上,
 * 重发会产生重复收藏夹 / 把点赞翻回去,换账号更会把写入落到别人头上。
 * 失败即终点,由调用方决定要不要让用户重来。
 */
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
