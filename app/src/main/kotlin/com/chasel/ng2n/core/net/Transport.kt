package com.chasel.ng2n.core.net

enum class HttpMethod { GET, POST }

class HttpRequest(
  val url: String,
  val method: HttpMethod,
  val headers: Map<String, String>,
  val body: ByteArray? = null,
  val contentType: String? = null,
  val credential: Credential? = null,
)

class HttpResponse(
  val status: Int,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray = ByteArray(0),
) {
  val contentType: String? get() = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
}

fun interface Transport {
  suspend fun execute(request: HttpRequest): HttpResponse
}

interface TransportFactory {

  fun create(): Transport

  fun renew(): Transport
}
