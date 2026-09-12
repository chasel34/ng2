package com.chasel.ng2n.core.net

enum class AuthMode {
  FORM,

  COOKIE,

  BOTH,

  NONE,
}

class AuthAttachment(
  val headers: Map<String, String>,
  val form: Map<String, String>,
) {
  val usesCookieChannel: Boolean get() = headers.containsKey(COOKIE_HEADER)

  override fun equals(other: Any?): Boolean =
    other is AuthAttachment && headers == other.headers && form == other.form

  override fun hashCode(): Int = 31 * headers.hashCode() + form.hashCode()

  override fun toString(): String = "AuthAttachment(headers=$headers, form=$form)"
}

const val COOKIE_HEADER = "Cookie"

const val COOKIE_PASSPORT_UID = "ngaPassportUid"

const val COOKIE_PASSPORT_CID = "ngaPassportCid"

const val FORM_ACCESS_UID = "access_uid"
const val FORM_ACCESS_TOKEN = "access_token"

private val EMPTY = AuthAttachment(emptyMap(), emptyMap())

fun cookieHeaderValue(credential: Credential): String =
  "$COOKIE_PASSPORT_UID=${credential.uid}; $COOKIE_PASSPORT_CID=${credential.token}"

fun buildAuthAttachment(mode: AuthMode, credential: Credential?): AuthAttachment {
  if (mode == AuthMode.NONE || credential == null) return EMPTY
  if (credential.uid.isEmpty() || credential.token.isEmpty()) return EMPTY

  val headers = mapOf(COOKIE_HEADER to cookieHeaderValue(credential))
  val form = mapOf(
    FORM_ACCESS_UID to credential.uid,
    FORM_ACCESS_TOKEN to credential.token,
  )

  return when (mode) {
    AuthMode.COOKIE -> AuthAttachment(headers, emptyMap())
    AuthMode.FORM -> AuthAttachment(emptyMap(), form)
    else -> AuthAttachment(headers, form)
  }
}
