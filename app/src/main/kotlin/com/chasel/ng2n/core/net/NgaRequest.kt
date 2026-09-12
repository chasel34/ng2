package com.chasel.ng2n.core.net

enum class Operation {
  READ,

  WRITE,
  ;

  val defaultAccountPolicy: AccountPolicy
    get() = if (this == WRITE) AccountPolicy.PINNED else AccountPolicy.FALLBACK
}

enum class AccountPolicy { PINNED, FALLBACK }

@JvmInline
value class CredentialOverride(val credential: Credential?) {
  companion object {
    val GUEST = CredentialOverride(null)
  }
}

class NgaRequest(
  val path: String,

  val operation: Operation,

  val query: QueryParams = emptyMap(),

  val form: QueryParams = emptyMap(),

  val method: HttpMethod = HttpMethod.POST,

  val accountPolicy: AccountPolicy = operation.defaultAccountPolicy,

  val format: ResponseFormat? = null,

  val formats: List<ResponseFormat>? = null,

  val userAgent: UserAgentProfile? = null,

  val auth: AuthMode? = null,

  val credential: CredentialOverride? = null,

  val host: String? = null,

  val shape: EnvelopeShape = EnvelopeShape.WRAPPED,

  val validate: ((NgaEnvelope) -> String?)? = null,

  val referer: String? = null,

  val refererPath: String? = null,
) {

  val isWrite: Boolean get() = operation == Operation.WRITE

  fun cacheScope(uid: String?): String {
    val scope = if (uid.isNullOrEmpty()) "guest" else uid
    val business = query.entries
      .filterNot { it.key.startsWith("__") }
      .sortedBy { it.key }
      .mapNotNull { (key, value) -> queryScopeValue(value)?.let { "$key=$it" } }
      .joinToString("&")
    return if (business.isEmpty()) "$scope|$path" else "$scope|$path?$business"
  }
}

private fun queryScopeValue(value: QueryValue?): String? = when (value) {
  null -> null
  is QueryValue.Flag -> if (value.value) "1" else null
  is QueryValue.Num -> value.value.toString()
  is QueryValue.Gbk -> value.value.ifEmpty { null }
  is QueryValue.Text -> value.value.ifEmpty { null }
}

class NgaResult(
  val envelope: NgaEnvelope,
  val via: String,
) {
  val root get() = envelope.root
  val data get() = envelope.data
  val time get() = envelope.time
  val fakeError get() = envelope.fakeError
}
