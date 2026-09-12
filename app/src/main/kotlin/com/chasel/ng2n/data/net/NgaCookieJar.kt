package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.COOKIE_PASSPORT_CID
import com.chasel.ng2n.core.net.COOKIE_PASSPORT_UID
import com.chasel.ng2n.core.net.Credential
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class NgaCookieJar(private val credential: () -> Credential?) : CookieJar {

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val current = credential() ?: return emptyList()
    if (current.uid.isEmpty() || current.token.isEmpty()) return emptyList()
    return listOf(
      cookie(url, COOKIE_PASSPORT_UID, current.uid),
      cookie(url, COOKIE_PASSPORT_CID, current.token),
    )
  }

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

  private fun cookie(url: HttpUrl, name: String, value: String): Cookie = Cookie.Builder()
    .name(name)
    .value(value)
    .hostOnlyDomain(url.host)
    .path("/")
    .build()
}

class CurrentCredentialCache {

  @Volatile
  private var value: Credential? = null

  fun peek(): Credential? = value

  fun remember(credential: Credential?) {
    value = credential
  }
}
