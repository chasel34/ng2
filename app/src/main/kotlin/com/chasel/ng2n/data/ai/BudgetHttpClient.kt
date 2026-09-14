package com.chasel.ng2n.data.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import io.github.oshai.kotlinlogging.slf4j.toKLogger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class BudgetHttpFactory(private val budget: AiRunBudget?) : KoogHttpClient.Factory {
  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  override fun create(clientName: String, baseUrl: String, headers: Map<String, String>,
    queryParameters: Map<String, String>, requestTimeoutMillis: Long, connectTimeoutMillis: Long,
    socketTimeoutMillis: Long, json: Json): KoogHttpClient {
    var retryAfterMillis: Long? = null
    val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
      .callTimeout(requestTimeoutMillis.coerceAtMost(90_000), TimeUnit.MILLISECONDS)
      .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS).readTimeout(socketTimeoutMillis, TimeUnit.MILLISECONDS)
      .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { response ->
        retryAfterMillis = response.header("Retry-After")?.let(::retryAfterMillis)
      } }.build()
    // Koog 的默认 HTTP 日志会输出服务端错误正文，不能用于携带用户资料的请求。
    val delegate = OkHttpKoogHttpClient(clientName, org.slf4j.helpers.NOPLogger.NOP_LOGGER.toKLogger(),
      http, json, baseUrl, headers, queryParameters)
    return StreamingKoogHttpClient(delegate, http, json, baseUrl, headers, queryParameters, budget) { retryAfterMillis }
  }
}

/**
 * 流式路径不走 Koog 1.2.0 的 OkHttp 实现：它用 `trySend` 把 SSE 事件推进 callbackFlow 的有界缓冲，
 * 消费端慢于服务端时静默丢帧（正文错乱、结束帧丢失）；其 `onFailure` 又在 OkHttp 线程上读取
 * okhttp 5 已剥离的 EventSource 响应体，取消时抛出未捕获异常。这里直接用 OkHttp 请求并自行解析 SSE，
 * 事件以挂起方式下发，背压回到 socket 读取。
 */
private class StreamingKoogHttpClient(
  private val delegate: KoogHttpClient,
  private val http: OkHttpClient,
  private val json: Json,
  private val baseUrl: String,
  private val defaultHeaders: Map<String, String>,
  private val queryParameters: Map<String, String>,
  private val budget: AiRunBudget?,
  private val retryAfterMillis: () -> Long?,
) : KoogHttpClient by delegate {
  override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>,
    responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R =
    delegate.post(path, requestBody, requestBodyType, responseType, parameters, jsonBodyHeaders(headers))

  override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>,
    parameters: Map<String, String>, headers: Map<String, String>): Flow<String> =
    body(path, requestBody, requestBodyType, parameters, headers, streaming = false)
      .mapNotNull { line -> line.takeIf { it.isNotBlank() } }

  override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
    dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
    parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
    currentCoroutineContext().ensureActive()
    if (budget?.transportStarted == true) budget.retryAttempt()
    budget?.sending()
    sseEvents(body(path, requestBody, requestBodyType, parameters, headers, streaming = true)).collect { data ->
      if (!dataFilter(data)) return@collect
      val raw = data.trim()
      runCatching { Json.parseToJsonElement(raw).jsonObject["usage"] as? JsonObject }.getOrNull()
        ?.let { budget?.rawUsage = it }
      processStreamingChunk(decodeStreamingResponse(raw))?.let { emit(it) }
    }
  }

  private fun <T : Any> body(path: String, requestBody: T, requestBodyType: KClass<T>,
    parameters: Map<String, String>, headers: Map<String, String>, streaming: Boolean): Flow<String> {
    val payload = requestPayload(requestBody, requestBodyType)
    val merged = defaultHeaders + mapOf("Content-Type" to "application/json") +
      (if (streaming) mapOf("Accept" to "text/event-stream", "Cache-Control" to "no-cache") else emptyMap()) + headers
    val request = Request.Builder().url(url(path, parameters))
      .apply { merged.forEach { (name, value) -> header(name, value) } }.post(payload).build()
    return read(request)
  }

  // 只按行下发；调用方分别解析 SSE 事件或整行 JSON。
  private fun read(request: Request): Flow<String> = flow {
    val call = http.newCall(request)
    val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
    try {
      val response = try { call.execute() } catch (e: IOException) { currentCoroutineContext().ensureActive(); throw transport(null, e) }
      response.use {
        if (!it.isSuccessful) throw transport(it.code, null)
        val source = it.body.source()
        while (true) {
          currentCoroutineContext().ensureActive()
          val line = try { source.readUtf8Line() } catch (e: IOException) { currentCoroutineContext().ensureActive(); throw transport(null, e) }
          emit(line ?: break)
        }
      }
    } finally { cancellation?.dispose() }
  }.flowOn(Dispatchers.IO)

  private fun transport(status: Int?, cause: IOException?): Throwable {
    if (status in listOf(429, 500, 503) && budget?.outputStarted != true && (retryAfterMillis() ?: 0) <= 10_000)
      return IllegalStateException("AI_RETRY_HTTP $status retryAfterMs=${retryAfterMillis() ?: 0}")
    val message = "模型 HTTP ${status ?: "结果不明"}"
    if (budget != null && com.chasel.ng2n.core.ai.rejectedHttpStatus(message) != null) budget.serverRejected = true
    return IllegalStateException(message, cause)
  }

  private fun url(path: String, parameters: Map<String, String>): HttpUrl {
    val resolved = when {
      path.startsWith("http://") || path.startsWith("https://") -> path
      path.isBlank() -> baseUrl
      else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }
    return resolved.toHttpUrl().newBuilder()
      .apply { (queryParameters + parameters).forEach { (name, value) -> addQueryParameter(name, value) } }.build()
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T : Any> requestPayload(requestBody: T, requestBodyType: KClass<T>): RequestBody {
    // OpenAI 基类把 chat 请求序列化成 String 再传下来，媒体类型必须由这里声明。
    val text = if (requestBodyType == String::class) requestBody as String
      else json.encodeToString(kotlinx.serialization.serializer(requestBodyType.java) as kotlinx.serialization.SerializationStrategy<T>, requestBody)
    return text.toRequestBody("application/json".toMediaType())
  }
}

// 每条 SSE 事件的 data 字段按规范按行累积，空行结束一条事件。
internal fun sseEvents(lines: Flow<String>): Flow<String> = flow {
  val data = StringBuilder()
  suspend fun dispatch() { if (data.isNotEmpty()) { emit(data.toString()); data.setLength(0) } }
  lines.collect { raw ->
    val line = raw.removeSuffix("\r")
    when {
      line.isEmpty() -> dispatch()
      line.startsWith(":") -> Unit
      else -> {
        val field = line.substringBefore(':')
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        if (field == "data") { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
      }
    }
  }
  dispatch()
}

// Koog 的 OkHttp 客户端把 String 请求体默认标成 text/plain，而 OpenAI 基类正是以 String 传递 JSON，DeepSeek 会返回 415。
private fun jsonBodyHeaders(headers: Map<String, String>): Map<String, String> =
  if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) headers else headers + ("Content-Type" to "application/json")

internal fun retryAfterMillis(value: String): Long? = value.toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
  ?: runCatching { (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
    .toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull()
