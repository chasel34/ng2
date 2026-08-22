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

/** 只有 `read.php` 有网页反解器(API 文档 §0.8:这一档是 read.php 专用)。 */
private const val SUPPORTED_PATH = "read.php"

/**
 * 网页版 HTML → 与 `__output=8` 同构的信封。
 *
 * 本体在 `core/net/web/ReadHtml.kt`(票 08;`commonui.postArg.proc` 的参数位置表
 * 全网无第二份文档,2026-08-22 对线上重验过)。这里是**注入点**:策略壳、四档档位、
 * 域名沿用规则、`only` 档的终点语义都由票 06 落地并有单测钉着,反解本体换掉不影响它们。
 */
interface WebReadParser {

  /**
   * 反解器就绪了吗。为 false 时这一档在链上直接让位,
   * **不白打一次网络请求**(打了也解不出来,只是给 NGA 多送一次限流计数)。
   */
  val available: Boolean

  /**
   * 契约同 `parseNgaJson`:解不出来抛 `kind = PARSE`(可重试),
   * 服务端语义错误(`<!--msgcodestart-->`)抛 `kind = SERVER`(不重试)。
   */
  fun parse(text: String, via: String): NgaEnvelope
}

/** 现行实现:`core/net/web/ReadHtml.kt` 的整页反解器(票 08)。 */
object ReadHtmlWebParser : WebReadParser {

  override val available: Boolean = true

  override fun parse(text: String, via: String): NgaEnvelope = parseReadPageHtml(text, via)
}

/**
 * 「反解器缺席」那一档。
 *
 * 票 08 之前它是唯一的实现;现在留着是因为**让位这条规则本身要有回归线**——
 * 反解本体将来若因为 NGA 改版被临时摘掉([WebReadParser.available] 置 false),
 * 链必须是「跳过这一档、错误仍是上一档那个」,而不是「多打一次请求再报 unavailable」。
 */
object UnavailableWebReadParser : WebReadParser {

  override val available: Boolean = false

  override fun parse(text: String, via: String): NgaEnvelope =
    throw NgaError(NgaErrorKind.UNAVAILABLE, "网页反解器没接上", via = via)
}

/**
 * 反封锁链的 Web 反解档(ADR-0002 / API 文档 §0.8)。
 * 直译 `src/core/net/strategies/web-fallback.ts`。
 *
 * 拿掉格式参数就是给浏览器看的那张网页,数据仍以内联 JS 的形态躺在里面。所以这一档
 * 与前面几档的差别只是**换一种响应格式**,请求本身照旧走 [runAttempt] ——
 * 拼 URL、附认证、GBK 解码、失败分类全是同一套。
 *
 * 只试一次、不枚举组合:前面 format-rotation 已经把域名空间跑完了,这一档变的是格式。
 *
 * @param placement 这一条在链上占的位置。**同一档位要在链上放两次**:
 *   `primary` 那条排在最前(`PRIMARY` / `ONLY` 时才真跑),
 *   `secondary` 那条排在换账号之后(`SECONDARY` 时才真跑)。
 *   档位是用户设置,链的顺序是装配时定死的,只能这么接。
 */
class WebFallbackStrategy(
  private val placement: Placement,
  private val parser: WebReadParser = ReadHtmlWebParser,
) : FetchStrategy {

  enum class Placement { PRIMARY, SECONDARY }

  override val name: String = WEB_FALLBACK_STRATEGY_NAME

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    // 别的接口没有反解器,什么档位都轮不到这一档——包括 `ONLY`:
    // 那个档位说的是「read.php 只走反解」,不是「整个 app 断网」
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
      // 域名沿用上一档试通的那个(没有就用默认):这一档换的是格式,不是域名
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
    // `ONLY` 档说好了不碰原生接口,所以这一档失败就是终点——
    // 标成不可重试,`runStrategyChain` 会当场收手而不是接着往下试
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

  /** 这个位置在当前档位下要不要跑。 */
  private fun activeAt(mode: WebFallbackMode): Boolean = when (placement) {
    Placement.PRIMARY -> mode == WebFallbackMode.PRIMARY || mode == WebFallbackMode.ONLY
    Placement.SECONDARY -> mode == WebFallbackMode.SECONDARY
  }

  private fun unavailable(message: String): StrategyOutcome = StrategyOutcome.Failed(
    NgaError(NgaErrorKind.UNAVAILABLE, message, via = WEB_FALLBACK_STRATEGY_NAME),
  )
}
