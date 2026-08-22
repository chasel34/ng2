package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.COOKIE_PASSPORT_CID
import com.chasel.ng2n.core.net.COOKIE_PASSPORT_UID
import com.chasel.ng2n.core.net.Credential
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 自管 CookieJar(**修 P1-03**)。
 *
 * ## 它解决的那个 P0
 *
 * okhttp 的 `BridgeInterceptor` 对 `Cookie` 是**无条件覆盖**:
 * `if (cookies.isNotEmpty()) requestBuilder.header("Cookie", …)` —— `Host` / `User-Agent` /
 * `Accept-Encoding` 才有「请求里已经有了就不覆盖」的守卫。RN 版挂的 jar 是 WebView 的
 * `CookieManager`(`ExpoFetchModule` 里的 `JavaNetCookieJar(ForwardingCookieHandler)`),
 * 于是反封锁链一换到镜像域名,jar 里没有 passport cookie、NGA 又下发一枚自己的 cookie,
 * 我们手拼的 `Cookie` 头就被整条顶掉,**请求静默变成游客**(ADR-0002 第 4 条)。
 *
 * 这一版把两个来源收成一个:**请求头里不再手写 `Cookie`**,凭证跟着
 * `HttpRequest.credential` 走,由本 jar 装成 cookie —— jar 是唯一来源,没得可顶。
 * `access_uid` / `access_token` 表单字段那条通道照抄不动(它不过 cookie jar)。
 *
 * ## 与 WebView 的 CookieManager 彻底分家
 *
 * 本 jar **不碰** `android.webkit.CookieManager`(那是票 15 登录收割点的事),
 * 也**不保存**服务端下发的任何 cookie:
 *
 * - RN 版之所以会保存,只是因为它复用了 WebView 的 jar,不是有意设计;
 *   而那份「保存」正是上面那个 P0 的直接成因。
 * - TS 侧真正被依赖的凭证通道只有两条(`auth.ts` 的 Cookie 头 + form 字段),
 *   `transport.ts` 自己一个 jar 都没有 —— 说明会话 cookie 对协议层不是必需品。
 * - 每一发请求的身份都由调用方显式给出,不存在「靠上一发留下的 cookie 续命」的路径;
 *   保存反而会让「换域名 = 换身份」这件事重新变得不可预测。
 *
 * 所以 [saveFromResponse] 是**故意的空实现**。将来真抓到「NGA 要求带回某个会话 cookie」
 * 的样本,再在这里加一个**按 client 隔离的内存 jar**,仍然不要碰 WebView 那份。
 *
 * ## 身份从哪来
 *
 * [credential] 是个函数而不是快照:协议层每一发请求都会派生一个只服务于这一发的 client
 * (见 `OkHttpTransport`),闭包里就是那一发钉死的身份;
 * 图片管线那种拿不到协程上下文的地方接的是 [CurrentCredentialCache],读最近一次的现值。
 */
class NgaCookieJar(private val credential: () -> Credential?) : CookieJar {

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val current = credential() ?: return emptyList()
    if (current.uid.isEmpty() || current.token.isEmpty()) return emptyList()
    return listOf(
      cookie(url, COOKIE_PASSPORT_UID, current.uid),
      cookie(url, COOKIE_PASSPORT_CID, current.token),
    )
  }

  /** **故意什么都不做**:服务端下发的 cookie 一律丢弃,见类注释。 */
  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

  private fun cookie(url: HttpUrl, name: String, value: String): Cookie = Cookie.Builder()
    .name(name)
    .value(value)
    // 只发给当前这台主机:NGA 的镜像域名彼此不是子域,domain() 反而会被拒
    .hostOnlyDomain(url.host)
    .path("/")
    .build()
}

/**
 * 「最近一次用过的当前账号」。
 *
 * 图片管线(票 12)与协议层共用同一个 [okhttp3.OkHttpClient],但它取图时拿不到
 * 协程上下文去 `suspend` 读 DataStore —— 而附件域名要带登录态才拿得到部分图。
 * 协议层每发一次请求就把身份写进这里,图片侧读现值。
 *
 * 不是权威来源(权威来源是 `AccountStore`),只是一个非阻塞的近似值;
 * 冷启动第一发协议请求之前它是 null,那时按游客取图,与 RN 版行为一致。
 */
class CurrentCredentialCache {

  @Volatile
  private var value: Credential? = null

  fun peek(): Credential? = value

  fun remember(credential: Credential?) {
    value = credential
  }
}
