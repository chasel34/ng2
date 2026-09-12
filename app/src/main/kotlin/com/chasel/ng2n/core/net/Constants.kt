package com.chasel.ng2n.core.net

val NGA_HOSTS: List<String> = listOf(
  "https://bbs.nga.cn",
  "https://ngabbs.com",
  "https://bbs.ngacn.cc",
  "https://nga.178.com",
  "https://nga.donews.com",
)

val DEFAULT_NGA_HOST: String = NGA_HOSTS[0]

enum class UserAgentProfile(
  val wire: String,
) {
  OFFICIAL("official"),

  WEBVIEW("webview"),

  WINDOWS_PHONE("windowsPhone"),

  DESKTOP("desktop"),
  ;

  companion object {
    fun fromWire(value: String?): UserAgentProfile? = entries.firstOrNull { it.wire == value }
  }
}

val USER_AGENT_PROFILES: Map<UserAgentProfile, String> = mapOf(
  UserAgentProfile.OFFICIAL to "Nga_Official/80024(Android12)",
  UserAgentProfile.WEBVIEW to
    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0.0.0 Mobile Safari/537.36",
  UserAgentProfile.WINDOWS_PHONE to "NGA_WP_JW/(;WINDOWS)",
  UserAgentProfile.DESKTOP to
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0.0.0 Safari/537.36",
)

class UserAgents(private val webView: () -> String) {

  operator fun get(profile: UserAgentProfile): String = when (profile) {
    UserAgentProfile.WEBVIEW -> webView()
    else -> USER_AGENT_PROFILES.getValue(profile)
  }

  companion object {
    fun fallback(): UserAgents =
      UserAgents { USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW) }

    fun fixed(webView: String): UserAgents = UserAgents { webView }
  }
}

const val X_USER_AGENT_VALUE = "Nga_Official"

enum class ResponseKind { JSON, XML, HTML }

enum class ResponseFormat(
  val wire: String,
  val kind: ResponseKind,
  val params: QueryParams,
) {
  JSON("json", ResponseKind.JSON, queryOf("__output" to "8")),

  JSON_VERBOSE("jsonVerbose", ResponseKind.JSON, queryOf("__output" to "11")),

  JSON_LITE("jsonLite", ResponseKind.JSON, queryOf("lite" to "js")),

  XML("xml", ResponseKind.XML, queryOf("lite" to "xml")),

  XML_COMPACT("xmlCompact", ResponseKind.XML, queryOf("__output" to "10")),

  HTML("html", ResponseKind.HTML, emptyMap()),
  ;

  val isJson: Boolean get() = kind == ResponseKind.JSON

  companion object {
    fun fromWire(value: String?): ResponseFormat? = entries.firstOrNull { it.wire == value }
  }
}

fun formatParamsOf(format: ResponseFormat): String =
  if (format.params.isEmpty()) {
    "(无格式参数)"
  } else {
    format.params.entries.joinToString("&") { (key, value) ->
      "$key=${(value as? QueryValue.Text)?.value.orEmpty()}"
    }
  }
