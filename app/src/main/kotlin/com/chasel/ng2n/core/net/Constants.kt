package com.chasel.ng2n.core.net

/**
 * 反封锁链的常量表。直译 `src/core/net/constants.ts`。
 *
 * 「假错误」与「身份没带上」两张白名单**不在这里**——票 04 已经把它们放进 `Errors.kt`
 * ([FAKE_ERROR_MESSAGES] / [AUTH_LEVEL_SERVER_MESSAGES]),那边的注释是判据出处,
 * 这里不再抄一份。
 */

/**
 * NGA 官方域名(API 文档 §0.1)。第一个是默认域名。
 *
 * **这里是唯一真相源**:票 14 曾把它暂居在 `data/settings/Settings.kt`,
 * 那边现在改成引用本表(设置项「NGA 域名」的校验只用到「必须是表里的一个」)。
 */
val NGA_HOSTS: List<String> = listOf(
  "https://bbs.nga.cn",
  "https://ngabbs.com",
  "https://bbs.ngacn.cc",
  "https://nga.178.com",
  "https://nga.donews.com",
)

val DEFAULT_NGA_HOST: String = NGA_HOSTS[0]

/**
 * UA 档位(API 文档 §0.3)。服务端校验客户端身份,必须伪装。
 *
 * Android v4 的现行做法是 UA 用系统 WebView UA、身份放辅助头 `X-User-Agent: Nga_Official`,
 * 所以 [WEBVIEW] 档的值由设备侧注入([UserAgents] 的 `webView` 取
 * `WebSettings.getDefaultUserAgent()`);[USER_AGENT_PROFILES] 里的常量只是兜底。
 */
enum class UserAgentProfile(
  /** 诊断日志里的档位名,与 TS 的联合类型成员一一对应。 */
  val wire: String,
) {
  /** 官方安卓客户端 UA,写死版本号 */
  OFFICIAL("official"),

  /** 系统 WebView UA(值由设备侧注入) */
  WEBVIEW("webview"),

  /** MNGA 对 read.php 强制使用,实测更不容易被封 */
  WINDOWS_PHONE("windowsPhone"),

  /** 网页兜底用 */
  DESKTOP("desktop"),
  ;

  companion object {
    fun fromWire(value: String?): UserAgentProfile? = entries.firstOrNull { it.wire == value }
  }
}

/** 各档位的兜底取值(`webview` 档在设备上会被真实系统 UA 覆盖)。 */
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

/**
 * 一次请求实际能用的 UA 表。
 *
 * `webView` 是设备侧现取的系统 WebView UA —— core 层不认 `android.webkit`,
 * 所以收成一个 `() -> String`,由 data 层注入(见 `di/NetworkModule.kt`)。
 */
class UserAgents(private val webView: () -> String) {

  operator fun get(profile: UserAgentProfile): String = when (profile) {
    UserAgentProfile.WEBVIEW -> webView()
    else -> USER_AGENT_PROFILES.getValue(profile)
  }

  companion object {
    /** 拿不到系统 UA 时的兜底表。 */
    fun fallback(): UserAgents =
      UserAgents { USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW) }

    /** 固定一个 UA 串(单测断言用)。 */
    fun fixed(webView: String): UserAgents = UserAgents { webView }
  }
}

/** `X-User-Agent` 辅助头的值——客户端身份就靠它声明(API 文档 §0.3)。 */
const val X_USER_AGENT_VALUE = "Nga_Official"

/** 该格式的响应由谁解析。 */
enum class ResponseKind { JSON, XML, HTML }

/**
 * 返回格式(API 文档 §0.4):`params` 是要拼进 query 的格式参数,`kind` 决定响应该由谁解析。
 *
 * 同一接口支持多种格式,**被封时交替尝试可绕过**——这就是反封锁链(ADR-0002)
 * 「格式参数交替」那一档要遍历的集合。
 */
enum class ResponseFormat(
  /** 诊断日志与组合缓存里的档位名,与 TS 的键名一一对应。 */
  val wire: String,
  val kind: ResponseKind,
  val params: QueryParams,
) {
  /** 紧凑 JSON,nuke.php / app_api.php 通用 */
  JSON("json", ResponseKind.JSON, queryOf("__output" to "8")),

  /** 详细 JSON。**另一个序列化器**——`fid=414` 坏字节唯一救得了的那一档(ADR-0002 第 8 条) */
  JSON_VERBOSE("jsonVerbose", ResponseKind.JSON, queryOf("__output" to "11")),

  /** JS 变量赋值包裹的 JSON,Android 常用(前缀由 `sanitizeNgaJson` 剥掉) */
  JSON_LITE("jsonLite", ResponseKind.JSON, queryOf("lite" to "js")),

  /** XML,MNGA 对 thread/read/post/forum.php 的首选。**本项目还没有 XML 解析器** */
  XML("xml", ResponseKind.XML, queryOf("lite" to "xml")),

  /** 紧凑 XML,与 `lite=xml` 等价的备用格式 */
  XML_COMPACT("xmlCompact", ResponseKind.XML, queryOf("__output" to "10")),

  /** 不带格式参数 = 网页 HTML,Web 反解与网页兜底走这条 */
  HTML("html", ResponseKind.HTML, emptyMap()),
  ;

  /** 这个档位的响应能不能直接按 JSON 清洗 + 解析。 */
  val isJson: Boolean get() = kind == ResponseKind.JSON

  companion object {
    fun fromWire(value: String?): ResponseFormat? = entries.firstOrNull { it.wire == value }
  }
}

/** 组合的可读名,进诊断日志用(`json` 档实际发的是 `__output=8`)。 */
fun formatParamsOf(format: ResponseFormat): String =
  if (format.params.isEmpty()) {
    "(无格式参数)"
  } else {
    format.params.entries.joinToString("&") { (key, value) ->
      "$key=${(value as? QueryValue.Text)?.value.orEmpty()}"
    }
  }
