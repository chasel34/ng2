package com.chasel.ng2n.core.net

interface NetworkSettingsSource {

  suspend fun host(): String

  suspend fun webFallbackMode(): WebFallbackMode

  suspend fun readPhpUserAgent(): UserAgentProfile?

  companion object {
    fun defaults(): NetworkSettingsSource = object : NetworkSettingsSource {
      override suspend fun host(): String = DEFAULT_NGA_HOST
      override suspend fun webFallbackMode(): WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE
      override suspend fun readPhpUserAgent(): UserAgentProfile? = UserAgentProfile.WINDOWS_PHONE
    }
  }
}

enum class WebFallbackMode(val wire: String) {
  DISABLED("disabled"),
  SECONDARY("secondary"),
  PRIMARY("primary"),
  ONLY("only"),
  ;

  companion object {
    fun fromWire(value: String?): WebFallbackMode? = entries.firstOrNull { it.wire == value }
  }
}

val DEFAULT_WEB_FALLBACK_MODE: WebFallbackMode = WebFallbackMode.SECONDARY
