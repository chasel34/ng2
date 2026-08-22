package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import java.net.URI
import java.nio.charset.Charset

/**
 * 反封锁链单测共用的假件。
 *
 * 两套并存(票面要求):
 * 1. **纯 Kotlin 假 [Transport]**(本文件)—— 钉链的控制流:试了几个组合、什么顺序、
 *    缓存记了什么、错误怎么分类。不起服务端,毫秒级,可精确编排每一发的响应。
 * 2. **MockWebServer**(`data/net/OkHttpTransportTest`)—— 钉 okhttp 内部才看得见的事:
 *    `Cookie` 头到底有没有装上、`renew()` 是不是真换了连接、GBK 表单 Content-Type
 *    有没有原样发出去。假 Transport 证明不了这些。
 */

val UTF8_JS = "text/javascript; charset=UTF-8"
val GBK_JS = "text/javascript; charset=GBK"

fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

fun gbkBytes(text: String): ByteArray = text.toByteArray(Charset.forName("GB18030"))

/** 一份正常的响应体。 */
const val OK_JSON = """{"data":{"0":"ok"},"time":1}"""

/** 被封的典型表现:返回一坨 HTML,洗不成 JSON(ADR-0002)。 */
const val BLOCKED_HTML = "<html><body>403 Forbidden</body></html>"

/** 假响应的脚本片段。 */
class FakeResponse(
  val status: Int = 200,
  val contentType: String? = UTF8_JS,
  val body: ByteArray = utf8(OK_JSON),
)

fun blocked(): FakeResponse =
  FakeResponse(status = 403, contentType = "text/html", body = utf8(BLOCKED_HTML))

fun ok(json: String = OK_JSON): FakeResponse = FakeResponse(body = utf8(json))

/** 记录收到的请求,按脚本返回响应。 */
class RecordingTransport(private val respond: (HttpRequest) -> FakeResponse) : Transport {

  val requests = mutableListOf<HttpRequest>()

  override suspend fun execute(request: HttpRequest): HttpResponse {
    requests.add(request)
    val response = respond(request)
    val headers = LinkedHashMap<String, String>()
    response.contentType?.let { headers["Content-Type"] = it }
    return HttpResponse(status = response.status, headers = headers, body = response.body)
  }

  /** 这次请求用的组合,断言顺序时比生 URL 好读:`__output=8@https://bbs.nga.cn`。 */
  fun combos(): List<String> = requests.map { comboOf(it) }

  fun uids(): List<String?> = requests.map { it.credential?.uid }

  fun clear() = requests.clear()
}

/** 从 URL 里认出格式档:`lite=js` 或 `__output=N`。 */
fun formatOf(request: HttpRequest): String {
  val query = URI(request.url).rawQuery.orEmpty().split("&")
    .mapNotNull { pair -> pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    .toMap()
  val lite = query["lite"]
  return if (lite == null) "__output=${query["__output"]}" else "lite=$lite"
}

fun originOf(request: HttpRequest): String = URI(request.url).let { "${it.scheme}://${it.authority}" }

fun comboOf(request: HttpRequest): String = "${formatOf(request)}@${originOf(request)}"

/** 不带格式参数 = 网页版;带 `__output` / `lite` 的都是原生接口。 */
fun isWebRequest(request: HttpRequest): Boolean =
  !request.url.contains("__output=") && !request.url.contains("lite=")

/** 只有 `only` 这个组合是通的,其余一律返回封禁页。 */
fun onlyWorking(only: String): RecordingTransport =
  RecordingTransport { if (comboOf(it) == only) ok() else blocked() }

/** 一个可控的 [TransportFactory]:数得清 `renew()` 被调了几次。 */
class CountingTransportFactory(private val transport: Transport) : TransportFactory {

  var built = 0
    private set

  override fun create(): Transport {
    built += 1
    return transport
  }

  override fun renew(): Transport {
    built += 1
    return transport
  }
}

/** 固定凭证 / 账号表的 [CredentialSource]。 */
class FakeCredentials(
  var signedIn: Credential? = null,
  var accounts: List<Credential> = emptyList(),
) : CredentialSource {
  override suspend fun current(): Credential? = signedIn
  override suspend fun all(): List<Credential> = accounts
}

/** 可改的设置源(「每请求现读」的单测就靠改它)。 */
class FakeSettings(
  var hostValue: String = DEFAULT_NGA_HOST,
  var mode: WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE,
  var readPhpUa: UserAgentProfile? = null,
) : NetworkSettingsSource {
  override suspend fun host(): String = hostValue
  override suspend fun webFallbackMode(): WebFallbackMode = mode
  override suspend fun readPhpUserAgent(): UserAgentProfile? = readPhpUa
}

val ALICE = Credential(uid = "10000001", token = "cid-a")
val BOB = Credential(uid = "10000002", token = "cid-b")
val CAROL = Credential(uid = "10000003", token = "cid-c")

/** 单测里最常用的读请求构造器 —— `operation` 必填,这里一次写好。 */
fun readRequest(
  path: String,
  query: QueryParams = emptyMap(),
  form: QueryParams = emptyMap(),
  method: HttpMethod = HttpMethod.POST,
  format: ResponseFormat? = null,
  host: String? = null,
  auth: AuthMode? = null,
  credential: CredentialOverride? = null,
  userAgent: UserAgentProfile? = null,
  shape: EnvelopeShape = EnvelopeShape.WRAPPED,
  validate: ((NgaEnvelope) -> String?)? = null,
  referer: String? = null,
  refererPath: String? = null,
): NgaRequest = NgaRequest(
  path = path,
  operation = Operation.READ,
  query = query,
  form = form,
  method = method,
  format = format,
  host = host,
  auth = auth,
  credential = credential,
  userAgent = userAgent,
  shape = shape,
  validate = validate,
  referer = referer,
  refererPath = refererPath,
)

/** 建一个只有指定策略的 client(TS 侧 `createNgaFetcher({ strategies })` 的对应物)。 */
fun testClient(
  transport: Transport,
  strategies: List<FetchStrategy> = listOf(DirectStrategy()),
  credentials: FakeCredentials = FakeCredentials(),
  settings: FakeSettings = FakeSettings(),
  comboCache: ComboCache = InMemoryComboCache(),
  userAgents: UserAgents = UserAgents.fallback(),
  authMode: AuthMode = AuthMode.BOTH,
  transports: TransportFactory? = null,
  onDiagnostic: ((FetchDiagnostic) -> Unit)? = null,
): NgaClient = NgaClient(
  transports = transports ?: object : TransportFactory {
    override fun create(): Transport = transport
    override fun renew(): Transport = transport
  },
  credentials = credentials,
  settings = settings,
  userAgents = userAgents,
  comboCache = comboCache,
  readChain = strategies,
  authMode = authMode,
  onDiagnostic = onDiagnostic,
)

/**
 * 一档假策略:要么恒成功、要么恒返回给定错误,并数着自己被跑了几次。
 * TS 侧 `stubStrategy` 的对应物。
 */
class StubStrategy(
  override val name: String,
  private val error: NgaError? = null,
) : FetchStrategy {

  var calls = 0
    private set

  override suspend fun run(request: NgaRequest, context: FetchContext): StrategyOutcome {
    calls += 1
    val failure = error ?: return StrategyOutcome.Ok(
      NgaResult(
        NgaEnvelope(
          root = kotlinx.serialization.json.buildJsonObject {
            put("data", kotlinx.serialization.json.buildJsonObject { put("from", kotlinx.serialization.json.JsonPrimitive(name)) })
          },
          data = kotlinx.serialization.json.buildJsonObject {
            put("from", kotlinx.serialization.json.JsonPrimitive(name))
          },
        ),
        name,
      ),
    )
    return StrategyOutcome.Failed(failure)
  }
}

/** 跑一段代码,断言它抛了 [NgaError] 并把它交出来。 */
inline fun assertThrowsNga(block: () -> Unit): NgaError = try {
  block()
  throw AssertionError("期望抛 NgaError,但什么都没抛")
} catch (error: NgaError) {
  error
}

/** 一份只读缓存的假 store。 */
class FakeTopicCacheStore : TopicCacheReader {

  private val entries = mutableMapOf<String, String>()

  val reads = mutableListOf<TopicCacheKey>()

  fun put(tid: Long, page: Int, payload: String) {
    entries["$tid/$page"] = payload
  }

  override suspend fun read(key: TopicCacheKey): String? {
    reads.add(key)
    return entries["${key.tid}/${key.page}"]
  }
}
