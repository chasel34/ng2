package com.chasel.ng2n.core.net

/**
 * 认证(API 文档 §0.2)。直译 `src/core/net/auth.ts`。
 *
 * 凭证是 WebView 登录后拿到的两个 Cookie:`ngaPassportUid` → uid、`ngaPassportCid` → token。
 * 两种附加方式**等价,任选其一**:MNGA 走 POST form 字段,Android 走 Cookie 头。
 *
 * ⚠️ **不能只靠 `Cookie` 请求头**(2026-08-13 取证,「版块全空」排查):okhttp 的
 * `BridgeInterceptor` 对 `Cookie` 是无条件 `header()` 覆盖(`Host` / `Accept-Encoding` /
 * `User-Agent` 才有「已有就不覆盖」的守卫)——只要 cookie jar 对这个域名有任意一枚 cookie,
 * 手写的 `Cookie` 头就整条被顶掉。RN 版挂的 jar 是 WebView 的 `CookieManager`:
 * 在 `bbs.nga.cn` 上通常无害(登录过,jar 里本来就有 passport cookie),但反封锁链一换到
 * 镜像域名,jar 里没有 passport cookie,NGA 只要下发一枚自己的 cookie,
 * **从第二发起请求就静默变成游客**。所以默认档位是 [BOTH]:form 字段不经过 cookie jar,顶不掉。
 *
 * ## 与 RN 版的实现差异(语义不变)
 *
 * 本函数**原样保留**两个通道的产物([AuthAttachment.headers] 里那条 `Cookie` 也照拼,
 * 移植过来的 `AuthTest` 逐条对拍的就是它)。但真正发请求时,`Cookie` 头**不再由这里写进
 * headers**:`runAttempt` 把凭证放进 [HttpRequest.credential],由自管的
 * `data/net/NgaCookieJar` 装成 cookie —— jar 成为唯一来源,BridgeInterceptor 没得可顶
 * (**修 P1-03**)。`headers` 里有没有 `Cookie` 这一条,就是「这一档要不要走 cookie 通道」的判据。
 */

/** 凭证的附加方式。 */
enum class AuthMode {
  /** body 里带 `access_uid` / `access_token`(MNGA 的做法) */
  FORM,

  /** `Cookie: ngaPassportUid=…; ngaPassportCid=…`(Android 的做法) */
  COOKIE,

  /** 两样都带(默认)。见文件头:Cookie 头在 Android 上会被 okhttp 的 cookie jar 顶掉 */
  BOTH,

  /** 游客访问 */
  NONE,
}

class AuthAttachment(
  val headers: Map<String, String>,
  val form: Map<String, String>,
) {
  /**
   * 这一档要不要走 cookie 通道。走的话由传输层的自管 jar 装 cookie,
   * 而不是把 [headers] 里那条 `Cookie` 直接写进请求头(见文件头)。
   */
  val usesCookieChannel: Boolean get() = headers.containsKey(COOKIE_HEADER)

  override fun equals(other: Any?): Boolean =
    other is AuthAttachment && headers == other.headers && form == other.form

  override fun hashCode(): Int = 31 * headers.hashCode() + form.hashCode()

  override fun toString(): String = "AuthAttachment(headers=$headers, form=$form)"
}

const val COOKIE_HEADER = "Cookie"

/** Cookie 名:uid。 */
const val COOKIE_PASSPORT_UID = "ngaPassportUid"

/** Cookie 名:会话凭证(**绝不进日志**)。 */
const val COOKIE_PASSPORT_CID = "ngaPassportCid"

/** 表单字段名。 */
const val FORM_ACCESS_UID = "access_uid"
const val FORM_ACCESS_TOKEN = "access_token"

private val EMPTY = AuthAttachment(emptyMap(), emptyMap())

/** `Cookie` 头的值。自管 jar 与 [buildAuthAttachment] 共用这一份拼法。 */
fun cookieHeaderValue(credential: Credential): String =
  "$COOKIE_PASSPORT_UID=${credential.uid}; $COOKIE_PASSPORT_CID=${credential.token}"

/** 把凭证按指定方式拼成待附加的 header 与 form 字段。无凭证时等同游客。 */
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
