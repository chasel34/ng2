package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.CALL_TIMEOUT_MS
import com.chasel.ng2n.core.net.CONNECT_TIMEOUT_MS
import com.chasel.ng2n.core.net.HttpMethod
import com.chasel.ng2n.core.net.HttpRequest
import com.chasel.ng2n.core.net.HttpResponse
import com.chasel.ng2n.core.net.READ_TIMEOUT_MS
import com.chasel.ng2n.core.net.Transport
import com.chasel.ng2n.core.net.TransportFactory
import com.chasel.ng2n.core.net.WRITE_TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * `core/net` 的 [Transport] 的 okhttp 实装。
 *
 * ## 一发请求 = 一个派生 client
 *
 * `client.newBuilder()` 出来的 client **共享连接池与 dispatcher**(okhttp 的既定语义),
 * 所以这不是「每次新建一个 HTTP 栈」,只是换掉那一层配置。这么做是为了让
 * [NgaCookieJar] 能拿到**这一发**钉死的身份:`CookieJar.loadForRequest` 只收得到 URL,
 * 拿不到 request tag,而全局共享一个可变字段在并发请求下会串号
 * (首页同时拉几个版块、换账号那一档与主请求并行)。
 *
 * ## 超时预算(修 P2-06)
 *
 * 四档全部来自 `core/net/Timeouts.kt`,理由写在那里。
 *
 * ## 取消
 *
 * 走协程取消:[withContext] 的作用域被取消时,`Call.execute()` 所在线程被中断,
 * okhttp 抛 `IOException`,而 [kotlinx.coroutines.runInterruptible] 之外我们靠
 * `ensureActive` 的语义让 `CancellationException` 冒上去 —— 上层 `runAttempt`
 * 原样重抛,链当场停(RN 版的 `AbortError` 分支对应物)。
 */
class OkHttpTransport(private val client: OkHttpClient) : Transport {

  override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(request.url)
    for ((name, value) in request.headers) builder.header(name, value)

    when (request.method) {
      HttpMethod.GET -> builder.get()
      HttpMethod.POST -> {
        val bytes = request.body ?: ByteArray(0)
        // `ByteArray.toRequestBody` 不碰 charset —— body 已是 percent 编码后的 ASCII,
        // 而 Content-Type 必须原样发出去(`;charset=GBK` 那一档是 API 文档 §0.5 的要求)。
        // 用 `String.toRequestBody` 会被 okhttp 追加 `; charset=utf-8`,与 RN 版不一致。
        builder.post(bytes.toRequestBody(request.contentType?.toMediaType()))
      }
    }

    // 这一发的身份钉在派生 client 的 jar 里(见类注释)
    val call = client.newBuilder()
      .cookieJar(NgaCookieJar { request.credential })
      .build()
      .newCall(builder.build())

    call.execute().use { response ->
      val headers = LinkedHashMap<String, String>(response.headers.size)
      for (index in 0 until response.headers.size) {
        headers[response.headers.name(index)] = response.headers.value(index)
      }
      HttpResponse(
        status = response.code,
        headers = headers,
        // ⚠️ 纪律(ADR-0002):body **一次性**读完,不做二次读取 / 流复制
        body = response.body.bytes(),
      )
    }
  }
}

/**
 * [TransportFactory] 的 okhttp 实装 —— **`renewTransport` 的真实现**(ADR-0002 第 3 条)。
 *
 * ## 为什么这一档在原生上才有意义
 *
 * RN 版的 `renewTransport` 是**空操作**:`createFetchTransport(expoFetch)` 只新建了一个
 * JS 闭包,底下的 `OkHttpClient` 是 `ExpoFetchModule` 里 `by lazy` 的模块级单例,
 * 连接池 / dispatcher / cookie jar 全共享 —— MNGA 那条「每次重试前重建 HTTP client,
 * 因为这条连接可能已经被中间设备盯上」的对策,在 RN 上从来没有真正生效过,
 * 实际效果只有「换一次组合」。
 *
 * ## 这里怎么才算「真换」
 *
 * 连接复用的粒度是 [ConnectionPool]:换一个**新池**,池里没有任何空闲连接,
 * 下一发必然重新 TCP 握手。[Dispatcher] 也一并换掉(票面要求;它还带着
 * `maxRequestsPerHost` 与自己的线程池,同源同池会互相排队)。
 *
 * ## 怎么验证它真的换了连接
 *
 * 1. **单测**(`OkHttpTransportTest`):`mockwebserver3` 的 `RecordedRequest.connectionIndex`
 *    是服务端视角的第几条 TCP 连接 —— 同一个 transport 连发两次拿到同一个 index,
 *    `renew()` 之后再发一次 index 就变了。这是服务端实证,不是我们自己数的。
 * 2. **真机 / 模拟器 debug 日志**:把 [DEBUG_LOG_CONNECTIONS] 打开,
 *    每发一次请求会往 logcat 打一行
 *    `ng2n-net: conn=<connection.hashCode()> local=<本地端口> → <url>`。
 *    连续两次 `renew()` 后这两个值都必须变;没变就是池没换掉。
 *    (拿 `EventListener.connectionAcquired` 取 `Connection`,再从
 *    `connection.socket().localPort` 读本地端口。)
 */
class OkHttpTransportFactory(
  private val base: OkHttpClient,
  private val onRenew: (() -> Unit)? = null,
) : TransportFactory {

  override fun create(): Transport = OkHttpTransport(base)

  override fun renew(): Transport {
    onRenew?.invoke()
    return OkHttpTransport(
      base.newBuilder()
        .connectionPool(ConnectionPool())
        .dispatcher(Dispatcher())
        .build(),
    )
  }
}

/**
 * 打开后每发一次请求往 logcat 记一行连接身份(见 [OkHttpTransportFactory] 的验证方法 2)。
 * 默认关:它每请求一行,平时是噪音。
 */
const val DEBUG_LOG_CONNECTIONS = false

/** 按统一超时预算(修 P2-06)配一个基础 client。 */
fun ngaHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
  .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  // 反封锁链自己就是重试机制,okhttp 再悄悄重试一遍会让「试了几个组合」对不上账
  .retryOnConnectionFailure(false)
