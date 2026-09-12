package com.chasel.ng2n.core.net

/**
 * HTTP 传输层的最小接口。直译 `src/core/net/transport.ts`,只有两处有意的偏离:
 *
 * 1. **凭证跟着请求走**([HttpRequest.credential])而不是由调用方拼好 `Cookie` 头。
 *    RN 版把 `Cookie: ngaPassportUid=…` 直接写进 headers,结果被 okhttp 的
 *    `BridgeInterceptor` 用 cookie jar 无条件顶掉(ADR-0002 第 4 条,那次 P0 的成因)。
 *    这一版**让 jar 成为 Cookie 头的唯一来源**:core 只声明「这一发用哪个身份」,
 *    由 `data/net/NgaCookieJar` 在 okhttp 里把它变成 cookie。两个来源变一个,顶不掉了。
 * 2. 响应带上 [HttpResponse.headers](票面要求)。`Content-Type` 仍单列一个字段,
 *    因为解码器(票 03 的 `decodeResponseBody`)只认它。
 *
 * ⚠️ 纪律(ADR-0002):body 一律**一次性**读完,不做二次读取 / 流复制。
 * RN 侧的出处是 expo/expo#47762(同一个 response 读第二次会串流乱序);okhttp 这边
 * `ResponseBody` 本来也只能消费一次,纪律相同。
 */

/** HTTP 方法。NGA 全部接口 GET/POST 皆可(API 文档 §0.4),默认 POST。 */
enum class HttpMethod { GET, POST }

class HttpRequest(
  val url: String,
  val method: HttpMethod,
  /** 除 `Cookie` 外的全部请求头(UA / X-User-Agent / Referer / Content-Type)。 */
  val headers: Map<String, String>,
  /** 已 percent 编码的表单体;GET 与无表单时为 null。 */
  val body: ByteArray? = null,
  /** 表单体的 `Content-Type`(含 `;charset=GBK` 那一档),没有 body 时为 null。 */
  val contentType: String? = null,
  /**
   * 这一发请求要带的身份。null = 游客。
   * 传输层据此装 `ngaPassportUid` / `ngaPassportCid` 两枚 cookie,**不做别的**。
   */
  val credential: Credential? = null,
)

class HttpResponse(
  val status: Int,
  /** 响应头,键不区分大小写地取(同名多值只留最后一个;NGA 用不到多值头)。 */
  val headers: Map<String, String> = emptyMap(),
  /** 原始字节。响应可能是 GBK,所以不能让运行时按 UTF-8 直接给字符串。 */
  val body: ByteArray = ByteArray(0),
) {
  val contentType: String? get() = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
}

/** 发一次 HTTP 请求。挂起函数:okhttp 侧走 `Dispatchers.IO`,单测侧直接返回。 */
fun interface Transport {
  suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * 传输层工厂。`renew()` 是 ADR-0002 第 3 条点名的「原生白捡增益」。
 *
 * RN 版的 `renewTransport` 是**空操作**:`createFetchTransport(expoFetch)` 只新建了一个
 * JS 闭包,底下的 `OkHttpClient` 是 `ExpoFetchModule` 里 `by lazy` 的模块级单例,
 * 连接池 / dispatcher / cookie jar 全共享 —— 「换域名时换一条新 TCP 连接」这件事
 * 在 RN 上从来没有真正发生过。原生这边 `renew()` 是真的
 * (见 `data/net/OkHttpTransportFactory`:`newBuilder()` + 新 `ConnectionPool` + 新 `Dispatcher`)。
 */
interface TransportFactory {

  /** 链上第一发用的传输层(共用默认连接池)。 */
  fun create(): Transport

  /** 重试前重建:独立连接池 / dispatcher,于是下一发一定是**新连接**。 */
  fun renew(): Transport
}
