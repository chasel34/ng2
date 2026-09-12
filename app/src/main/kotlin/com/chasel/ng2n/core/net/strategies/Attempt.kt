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

/**
 * 链上所有直连策略共用的「发一次请求」。直译 `src/core/net/strategies/attempt.ts`。
 *
 * direct(单发)、format-rotation(枚举组合连发)、switch-account(换凭证再发一次)、
 * web-fallback(换成 HTML 再发一次)只在**发几次、用哪个组合、谁来解析**上不同,
 * 拼 URL / 附认证 / 解码 / 失败分类这套完全一样,所以收在这里一份。
 *
 * 失败分类那一半是票 04 的 `classifyHttpResponse`(纯函数、金样本对拍过),这里不重抄。
 */

/** 覆盖响应解析(Web 反解档用)。契约同 `parseNgaJson`:解不出来抛 PARSE,服务端语义错误抛 SERVER。 */
fun interface ResponseParser {
  fun parse(text: String): NgaEnvelope
}

/**
 * 这次请求该用哪个 UA 档位。
 *
 * `read.php` 可切 Windows Phone UA(ADR-0002,实测更不容易被封)——是**策略开关**,
 * 由设备侧从设置里读(默认开);单条请求显式写了 `userAgent` 的以它为准。
 */
fun resolveUserAgentProfile(request: NgaRequest, context: FetchContext): UserAgentProfile {
  request.userAgent?.let { return it }
  val override = context.readPhpUserAgent
  if (override != null && request.path.startsWith("read.php")) return override
  return UserAgentProfile.WEBVIEW
}

private fun unavailable(message: String, via: String): StrategyOutcome =
  StrategyOutcome.Failed(NgaError(NgaErrorKind.UNAVAILABLE, message, via = via))

/**
 * 发一次请求并把结果分类。**不抛异常**,一切失败都走 [StrategyOutcome]
 * (协程取消除外——那必须原样往上抛,否则结构化并发就断了)。
 *
 * @param credentialOverride 覆盖凭证(换账号重试用)。null = 不覆盖;
 *   `CredentialOverride(null)` = 强制游客。
 * @param transport 覆盖传输层(每次重试前重建 HTTP client 用)。
 * @param parse 覆盖响应解析(Web 反解档用)。给了它就不再限制格式档位。
 */
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
    // 没自带解析器就只会解 JSON 家族;HTML 档由 Web 反解那一档自带解析器进来(票 08),
    // XML 档至今没有解析器。标成可重试,链上真有能处理这个格式的策略时才轮得到它。
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
    // form 方式把凭证放 POST body,GET 没有 body——静默降级成游客请求太难查了。
    // `both` 档不用报错:GET 时 form 那一半带不上,但 cookie 通道还在
    return unavailable("form 认证方式要求 POST,这条请求写的是 GET;改用 auth = BOTH", via)
  }

  val userAgent = resolveUserAgentProfile(request, context)
  val userAgentValue = context.userAgents[userAgent]
  val headers = LinkedHashMap<String, String>(4)
  headers["User-Agent"] = userAgentValue
  // 客户端身份放辅助头(Android v4 的现行做法,API 文档 §0.3)
  headers["X-User-Agent"] = X_USER_AGENT_VALUE
  headers["Referer"] = request.referer ?: "$host/${request.refererPath.orEmpty()}"

  // ⚠️ `Cookie` **不进 headers**:由传输层的自管 jar 装(见 Auth.kt 文件头 / ADR-0002 第 4 条)。
  // `auth.headers` 里有没有那一条,就是「这一档要不要走 cookie 通道」的判据。
  val cookieCredential = if (auth.usesCookieChannel) credential else null

  var body: ByteArray? = null
  var contentType: String? = null
  if (request.method == HttpMethod.POST) {
    val formParams: QueryParams = LinkedHashMap<String, QueryValue?>(request.form).apply {
      for ((key, value) in auth.form) put(key, QueryValue.Text(value))
    }
    // 表单里有 GBK 值时要声明出来,否则服务端按 UTF-8 解 percent 字节(API 文档 §0.5)。
    // 判据只看 **request.form**(auth 那两个字段永远是 ASCII),与 RN 版一致。
    contentType = formContentType(request.form)
    // buildQueryString 的产物已是 percent 编码后的 ASCII,按 ISO-8859-1 落字节即原样。
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
    // 调用方主动取消不是「被封」,别让链继续往下试。
    // 协程取消必须原样抛(结构化并发),但**先报一条 attempt** 好让诊断看得见它停在哪。
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
