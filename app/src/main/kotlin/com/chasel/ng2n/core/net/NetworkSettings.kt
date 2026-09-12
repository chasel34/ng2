package com.chasel.ng2n.core.net

/**
 * 反封锁链**每请求现读**的那几项设置。
 *
 * 「现读」是 RN 版就有的语义(`getHost` / `getReadPhpUserAgent` / `webFallbackMode` 都是
 * 每次 fetch 现取):设置页改完,**下一个请求**就生效,不用重建 client;
 * 在途的那个请求仍按发起时的取值走。
 *
 * core 层不认 DataStore,所以收成一个接口,由 `data/net/SettingsNetworkSource` 实现。
 */
interface NetworkSettingsSource {

  /** 当前默认域名(设置页可改)。轮换时它永远排在官方域名表最前面。 */
  suspend fun host(): String

  /** Web 反解档位。 */
  suspend fun webFallbackMode(): WebFallbackMode

  /**
   * `read.php` 的 UA 档位覆盖;返回 null = 不覆盖,按默认的系统 WebView UA 走。
   * 实验室开关「read.php 用 Windows Phone UA」**默认开**。
   */
  suspend fun readPhpUserAgent(): UserAgentProfile?

  companion object {
    /** 全默认值(单测与还没接上设置时用)。 */
    fun defaults(): NetworkSettingsSource = object : NetworkSettingsSource {
      override suspend fun host(): String = DEFAULT_NGA_HOST
      override suspend fun webFallbackMode(): WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE
      override suspend fun readPhpUserAgent(): UserAgentProfile? = UserAgentProfile.WINDOWS_PHONE
    }
  }
}

/**
 * Web 反解档位(ADR-0002 / API 文档 §0.8 的四档)。
 *
 * - [DISABLED] 关掉,链上当这一档不存在
 * - [SECONDARY] 默认:排在换账号之后,原生全垮了才反解
 * - [PRIMARY] `read.php` 优先反解,反解不出来再退回原生
 * - [ONLY] 只走反解,原生一次都不打(排查「是不是被封」时用)
 *
 * **这里是唯一真相源**:票 14 曾在 `data/settings/Settings.kt` 定义过一份,
 * 那边现在改成引用本枚举。
 */
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

/** 默认档位:排在换账号之后,原生接口全垮了才去反解网页版。 */
val DEFAULT_WEB_FALLBACK_MODE: WebFallbackMode = WebFallbackMode.SECONDARY
