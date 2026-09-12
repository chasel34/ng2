package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import com.chasel.ng2n.core.net.strategies.FormatRotationStrategy
import com.chasel.ng2n.core.net.strategies.SwitchAccountStrategy
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.core.net.strategies.TopicCacheStrategy
import com.chasel.ng2n.core.net.strategies.WebFallbackStrategy
import com.chasel.ng2n.core.net.strategies.WebReadParser
import com.chasel.ng2n.core.net.strategies.UnavailableWebReadParser

/**
 * 全 app 共用的 NGA 请求器 —— 反封锁链在这里接上设备侧的 HTTP 实现。
 * 直译 `src/store/nga-client.ts` 的装配,外加**修 P1-01** 的读写分链。
 *
 * ## 链的顺序(ADR-0002,与 RN 版 `nga-client.ts:83-91` 逐档对齐)
 *
 * 1. `web-fallback`(primary 位)—— 只在档位为 PRIMARY / ONLY 时真跑
 * 2. `format-rotation` —— 格式参数 × 域名的组合枚举,成功组合按接口 key 缓存
 * 3. `switch-account` —— 换下一个已登录账号的 cookie 试一次(仅多账号)
 * 4. `web-fallback`(secondary 位,**默认档**)—— 原生全垮了才反解网页版
 * 5. `topic-cache` —— read.php 专用:从本机 Room 还原上次存下的那一页
 *
 * 链外还有第 6 步「用网页版打开」(用户手点的一个路由,归票 17),不在这条链上。
 *
 * `web-fallback` 在链上出现两次是刻意的:档位是用户设置,而链的顺序装配时就定死了,
 * 只能两个位置各摆一条、各自按档位决定跑不跑。
 *
 * ## 写操作走另一条链(修 P1-01)
 *
 * 写请求([Operation.WRITE])的链**只有 direct 一档**:不轮换、不换号、不重放。
 * 服务端可能已经执行了、只是响应在网络层丢了 —— 重发会产生重复收藏夹 / 把点赞翻回去,
 * 换账号更会把写入落到别人头上(审计 P1-01 的四步复现)。
 * format-rotation 与 switch-account 各自还有一道自查闸,装配错了也不会重放。
 *
 * ## 每请求现读
 *
 * 域名、Web 反解档位、`read.php` 的 UA 档位、当前账号 —— 每次 [execute] 现读一遍
 * (RN 版同款语义):设置页改完下一个请求就生效,切号之后下一个请求就用新 cookie,
 * 而**在途的那个请求仍按发起时的取值走**(P1-01 要求的「身份在生命周期内不变」)。
 */
class NgaClient(
  private val transports: TransportFactory,
  private val credentials: CredentialSource,
  private val settings: NetworkSettingsSource,
  private val userAgents: UserAgents,
  /** 成功组合缓存。建在外面是为了让 UI 能清它(详情页的「重试原生」要从头试探)。 */
  val comboCache: ComboCache = InMemoryComboCache(),
  private val readChain: List<FetchStrategy>,
  private val writeChain: List<FetchStrategy> = listOf(DirectStrategy()),
  private val authMode: AuthMode = AuthMode.BOTH,
  /** 整条链的诊断记录去处(设备侧写本地日志,实验室页导出)。 */
  private val onDiagnostic: ((FetchDiagnostic) -> Unit)? = null,
  private val onEvent: ((FetchEvent) -> Unit)? = null,
) {

  suspend fun execute(request: NgaRequest): NgaResult {
    val chain = if (request.isWrite) writeChain else readChain
    val context = FetchContext(
      transport = transports.create(),
      host = request.host ?: settings.host(),
      authMode = request.auth ?: authMode,
      // 身份在这里定格:链跑到一半用户切了号,这一发仍归发起它的账号
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

  /**
   * 忘掉某个接口上次试通的格式 × 域名组合(ADR-0002)。
   * key 即接口 key(`read.php`、`thread.php`、`nuke.php?__lib=…&__act=…`)。
   *
   * ⚠️ `thread.php` 这一条是**版块列表 / 搜索 / 收藏夹 / 热帖 / 精华区 / 某人的主题共用**的,
   * 清它等于让这半个 app 一起重新试探。
   */
  fun forgetSuccessfulCombo(interfaceKey: String) = comboCache.forget(interfaceKey)

  /** 本次运行里各接口当前挂在哪个组合上(实验室页的「本次运行的组合」)。 */
  fun successfulCombos(): List<Pair<String, ComboRecord>> = comboCache.entries()

  companion object {

    /**
     * ADR-0002 的五档标准装配。
     *
     * @param webReadParser 票 08 的网页反解器;不给就是占位实现(那两档在链上直接让位)。
     * @param topicCacheReader 票 14 的帖子缓存读口;不给就没有最后一档。
     */
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
