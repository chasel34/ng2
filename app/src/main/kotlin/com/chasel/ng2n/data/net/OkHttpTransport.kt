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

class OkHttpTransport(private val client: OkHttpClient) : Transport {

  override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(request.url)
    for ((name, value) in request.headers) builder.header(name, value)

    when (request.method) {
      HttpMethod.GET -> builder.get()
      HttpMethod.POST -> {
        val bytes = request.body ?: ByteArray(0)
        builder.post(bytes.toRequestBody(request.contentType?.toMediaType()))
      }
    }

    // 派生 client 共享连接池，但 CookieJar 固定本次凭证，避免并发串号。
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
        body = response.body.bytes(),
      )
    }
  }
}

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

const val DEBUG_LOG_CONNECTIONS = false

fun ngaHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
  .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  // 重试由策略链控制，避免底层自动重放写请求。
  .retryOnConnectionFailure(false)
