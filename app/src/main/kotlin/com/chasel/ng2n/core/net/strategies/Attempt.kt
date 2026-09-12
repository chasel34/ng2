package com.chasel.ng2n.core.net.strategies

import com.chasel.ng2n.core.net.AuthMode
import com.chasel.ng2n.core.net.COOKIE_HEADER
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialOverride
import com.chasel.ng2n.core.net.FetchCombo
import com.chasel.ng2n.core.net.FetchContext
import com.chasel.ng2n.core.net.FetchEvent
import com.chasel.ng2n.core.net.HttpClassification
import com.chasel.ng2n.core.net.HttpMethod
import com.chasel.ng2n.core.net.HttpRequest
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.NgaResult
import com.chasel.ng2n.core.net.QueryParams
import com.chasel.ng2n.core.net.StrategyOutcome
import com.chasel.ng2n.core.net.Transport
import com.chasel.ng2n.core.net.UserAgentProfile
import com.chasel.ng2n.core.net.X_USER_AGENT_VALUE
import com.chasel.ng2n.core.net.buildAuthAttachment
import com.chasel.ng2n.core.net.buildQueryString
import com.chasel.ng2n.core.net.classifyHttpResponse
import com.chasel.ng2n.core.net.encoding.decodeResponseBody
import com.chasel.ng2n.core.net.formContentType
import com.chasel.ng2n.core.net.outboundQuery
import com.chasel.ng2n.core.net.outboundUrl
import com.chasel.ng2n.core.net.QueryValue
import kotlinx.coroutines.CancellationException

fun interface ResponseParser {
  fun parse(text: String): NgaEnvelope
}

fun resolveUserAgentProfile(request: NgaRequest, context: FetchContext): UserAgentProfile {
  request.userAgent?.let { return it }
  val override = context.readPhpUserAgent
  if (override != null && request.path.startsWith("read.php")) return override
  return UserAgentProfile.WEBVIEW
}

private fun unavailable(message: String, via: String): StrategyOutcome =
  StrategyOutcome.Failed(NgaError(NgaErrorKind.UNAVAILABLE, message, via = via))

suspend fun runAttempt(
  request: NgaRequest,
  context: FetchContext,
  via: String,
  combo: FetchCombo,
  credentialOverride: CredentialOverride? = null,
  transport: Transport? = null,
  parse: ResponseParser? = null,
): StrategyOutcome {
  if (parse == null && !combo.format.isJson) {
    return unavailable("$via 只解析 JSON 家族格式,收到 ${combo.format.wire}", via)
  }

  val host = combo.host.trimEnd('/')
  val authMode = request.auth ?: context.authMode
  val credential: Credential? = when {
    credentialOverride != null -> credentialOverride.credential
    request.credential != null -> request.credential.credential
    else -> context.credential
  }
  val auth = buildAuthAttachment(authMode, credential)
  if (request.method == HttpMethod.GET && auth.form.isNotEmpty() && authMode == AuthMode.FORM) {
    return unavailable("form 认证方式要求 POST,这条请求写的是 GET;改用 auth = BOTH", via)
  }

  val userAgent = resolveUserAgentProfile(request, context)
  val userAgentValue = context.userAgents[userAgent]
  val headers = LinkedHashMap<String, String>(4)
  headers["User-Agent"] = userAgentValue
  headers["X-User-Agent"] = X_USER_AGENT_VALUE
  headers["Referer"] = request.referer ?: "$host/${request.refererPath.orEmpty()}"

  val cookieCredential = if (auth.usesCookieChannel) credential else null

  var body: ByteArray? = null
  var contentType: String? = null
  if (request.method == HttpMethod.POST) {
    val formParams: QueryParams = LinkedHashMap<String, QueryValue?>(request.form).apply {
      for ((key, value) in auth.form) put(key, QueryValue.Text(value))
    }
    contentType = formContentType(request.form)
    body = buildQueryString(formParams).toByteArray(Charsets.ISO_8859_1)
  }

  fun report(outcome: StrategyOutcome): StrategyOutcome {
    context.onEvent?.invoke(
      FetchEvent.Attempt(
        strategy = via,
        path = request.path,
        format = combo.format,
        host = host,
        userAgent = userAgent,
        userAgentValue = userAgentValue,
        uid = credential?.uid,
        error = (outcome as? StrategyOutcome.Failed)?.error,
      ),
    )
    return outcome
  }

  val wire = transport ?: context.transport
  val response = try {
    wire.execute(
      HttpRequest(
        url = outboundUrl(host, request.path, outboundQuery(request.query, combo.format.params)),
        method = request.method,
        headers = headers,
        body = body,
        contentType = contentType,
        credential = cookieCredential,
      ),
    )
  } catch (cancelled: CancellationException) {
    context.onEvent?.invoke(
      FetchEvent.Attempt(
        strategy = via,
        path = request.path,
        format = combo.format,
        host = host,
        userAgent = userAgent,
        userAgentValue = userAgentValue,
        uid = credential?.uid,
        error = NgaError(NgaErrorKind.NETWORK, "请求已取消", via = via, retryable = false),
      ),
    )
    throw cancelled
  } catch (cause: Exception) {
    return report(
      StrategyOutcome.Failed(
        NgaError(
          NgaErrorKind.NETWORK,
          cause.message ?: "网络请求失败",
          via = via,
          cause = cause,
        ),
      ),
    )
  }

  val text = decodeResponseBody(response.body, response.contentType)
  val classified = classifyHttpResponse(
    status = response.status,
    bodyText = text,
    via = via,
    shape = request.shape,
    validate = { envelope -> request.validate?.invoke(envelope) },
    parse = parse?.let { { body -> it.parse(body) } }
      ?: { body -> com.chasel.ng2n.core.net.parseNgaJson(body, via, request.shape) },
  )
  return when (classified) {
    is HttpClassification.Ok -> report(StrategyOutcome.Ok(NgaResult(classified.envelope, via)))
    is HttpClassification.Failed -> report(StrategyOutcome.Failed(classified.error))
  }
}
