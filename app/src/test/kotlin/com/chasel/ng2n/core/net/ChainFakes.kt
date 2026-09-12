package com.chasel.ng2n.core.net

import com.chasel.ng2n.core.net.strategies.DirectStrategy
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import java.net.URI
import java.nio.charset.Charset

val UTF8_JS = "text/javascript; charset=UTF-8"
val GBK_JS = "text/javascript; charset=GBK"

fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

fun gbkBytes(text: String): ByteArray = text.toByteArray(Charset.forName("GB18030"))

const val OK_JSON = """{"data":{"0":"ok"},"time":1}"""

const val BLOCKED_HTML = "<html><body>403 Forbidden</body></html>"

class FakeResponse(
  val status: Int = 200,
  val contentType: String? = UTF8_JS,
  val body: ByteArray = utf8(OK_JSON),
)

fun blocked(): FakeResponse =
  FakeResponse(status = 403, contentType = "text/html", body = utf8(BLOCKED_HTML))

fun ok(json: String = OK_JSON): FakeResponse = FakeResponse(body = utf8(json))

class RecordingTransport(private val respond: (HttpRequest) -> FakeResponse) : Transport {

  val requests = mutableListOf<HttpRequest>()

  override suspend fun execute(request: HttpRequest): HttpResponse {
    requests.add(request)
    val response = respond(request)
    val headers = LinkedHashMap<String, String>()
    response.contentType?.let { headers["Content-Type"] = it }
    return HttpResponse(status = response.status, headers = headers, body = response.body)
  }

  fun combos(): List<String> = requests.map { comboOf(it) }

  fun uids(): List<String?> = requests.map { it.credential?.uid }

  fun clear() = requests.clear()
}

fun formatOf(request: HttpRequest): String {
  val query = URI(request.url).rawQuery.orEmpty().split("&")
    .mapNotNull { pair -> pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    .toMap()
  val lite = query["lite"]
  return if (lite == null) "__output=${query["__output"]}" else "lite=$lite"
}

fun originOf(request: HttpRequest): String = URI(request.url).let { "${it.scheme}://${it.authority}" }

fun comboOf(request: HttpRequest): String = "${formatOf(request)}@${originOf(request)}"

fun isWebRequest(request: HttpRequest): Boolean =
  !request.url.contains("__output=") && !request.url.contains("lite=")

fun onlyWorking(only: String): RecordingTransport =
  RecordingTransport { if (comboOf(it) == only) ok() else blocked() }

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

class FakeCredentials(
  var signedIn: Credential? = null,
  var accounts: List<Credential> = emptyList(),
) : CredentialSource {
  override suspend fun current(): Credential? = signedIn
  override suspend fun all(): List<Credential> = accounts
}

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

inline fun assertThrowsNga(block: () -> Unit): NgaError = try {
  block()
  throw AssertionError("期望抛 NgaError,但什么都没抛")
} catch (error: NgaError) {
  error
}

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
