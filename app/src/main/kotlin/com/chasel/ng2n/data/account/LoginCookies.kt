package com.chasel.ng2n.data.account

import com.chasel.ng2n.core.net.encoding.REPLACEMENT_CHAR
import com.chasel.ng2n.core.net.encoding.decodeGb18030

/**
 * 从 WebView 的 cookie 串里认出登录凭证 —— `src/core/account/login-cookies.ts` +
 * `src/core/account/username.ts` 的直译(API 文档 §0.2)。
 *
 * 登录成功的标志是同时出现 `ngaPassportUid` 与 `ngaPassportCid` 两个 Cookie;
 * 登录**前**页面就会挂着占位值(uid 可能是 `guest`、cid 可能是短垃圾串),
 * 所以光看键存在不够,还得按取值形状过滤,否则 500ms 轮询会在登录前误报。
 *
 * 纯 Kotlin(与 [Accounts] 同层):`android.webkit.CookieManager` 那一侧在
 * [WebCookieVault],这里只认字符串。
 */

/** 解析 `k=v; k2=v2` 形式的 cookie 串。值里允许再出现 `=`(base64 之类)。 */
fun parseCookieString(cookie: String): Map<String, String> {
  val jar = LinkedHashMap<String, String>()
  for (part in cookie.split(';')) {
    val eq = part.indexOf('=')
    if (eq < 0) continue
    val key = part.substring(0, eq).trim()
    val value = part.substring(eq + 1).trim()
    if (key != "") jar[key] = value
  }
  return jar
}

data class LoginCookies(
  val uid: String,
  /** 会话凭证 `ngaPassportCid` */
  val cid: String,
  /** 原始 `ngaPassportUrlencodedUname`(GBK 双重 URLEncode),可能缺失 */
  val urlencodedUname: String?,
)

/** uid 必须是纯数字 —— 排掉 `guest` 这类未登录占位。 */
private val UID_PATTERN = Regex("^\\d+$")

/** cid 是长随机串(实测 40 位字母数字);短值/空值视为还没登录完。 */
private val CID_PATTERN = Regex("^[0-9A-Za-z_-]{16,}$")

/** cookie 串里有合法凭证就取出来,否则 null(表示登录还没完成)。 */
fun extractLoginCookies(cookie: String): LoginCookies? {
  val jar = parseCookieString(cookie)
  val uid = jar[COOKIE_PASSPORT_UID_NAME] ?: ""
  val cid = jar[COOKIE_PASSPORT_CID_NAME] ?: ""
  if (!UID_PATTERN.matches(uid) || !CID_PATTERN.matches(cid)) return null
  return LoginCookies(uid = uid, cid = cid, urlencodedUname = jar[COOKIE_UNAME_NAME])
}

/** cookie 名。与 `core/net/Auth.kt` 的两个常量同值,这里不 import 是为了让本文件保持零依赖可读。 */
private const val COOKIE_PASSPORT_UID_NAME = "ngaPassportUid"
private const val COOKIE_PASSPORT_CID_NAME = "ngaPassportCid"
private const val COOKIE_UNAME_NAME = "ngaPassportUrlencodedUname"

/**
 * `ngaPassportUrlencodedUname` 的解码:**GBK 字符集 URLDecode 两次**(API 文档 §0.2)。
 *
 * 服务端是 `URLEncode(URLEncode(name, GBK), GBK)` —— 第一层解出来仍是 `%XX` 的 ASCII 文本,
 * 第二层解出来才是 GBK 字节,最后过 GB18030 解码器。不能用 `decodeUriComponentOrNull`:
 * 它按 UTF-8 解字节,GBK 序列会直接判非法。
 *
 * 解不动(畸形/解出替换字符/空串)返回 null,调用方回落到 `UID <uid>` 展示。
 */
fun decodeLoginUsername(raw: String): String? {
  val once = percentDecodeToBytes(raw) ?: return null
  // 第一层的产物按单字节直转回文本(全 ASCII),再解第二层
  val asText = buildString(once.size) { for (byte in once) append((byte.toInt() and 0xff).toChar()) }
  val twice = percentDecodeToBytes(asText) ?: return null
  val name = decodeGb18030(twice).trim()
  if (name == "" || name.contains(REPLACEMENT_CHAR)) return null
  return name
}

/**
 * 单层 URLDecode 到字节。兼容 Java `URLEncoder` 的两个习惯:
 * `+` 表示空格、十六进制大小写不定。畸形输入(孤立的 `%`、非单字节字符)返回 null。
 */
private fun percentDecodeToBytes(input: String): ByteArray? {
  val bytes = ArrayList<Byte>(input.length)
  var index = 0
  while (index < input.length) {
    val char = input[index]
    if (char == '%') {
      // 「后面必须正好是两位十六进制」——不足两位、或含 `+`/`-` 这类 toIntOrNull 认的符号,
      // 都算畸形(TS 侧是 `/^[0-9a-fA-F]{2}$/`)
      if (index + 3 > input.length) return null
      val high = hexDigit(input[index + 1]) ?: return null
      val low = hexDigit(input[index + 2]) ?: return null
      bytes += ((high shl 4) or low).toByte()
      index += 3
      continue
    }
    if (char == '+') {
      bytes += 0x20.toByte()
      index += 1
      continue
    }
    // URL 编码的产物只可能是单字节字符,出现更宽的说明整串不是编码结果
    if (char.code > 0xff) return null
    bytes += char.code.toByte()
    index += 1
  }
  return bytes.toByteArray()
}

private fun hexDigit(char: Char): Int? = when (char) {
  in '0'..'9' -> char - '0'
  in 'a'..'f' -> char - 'a' + 10
  in 'A'..'F' -> char - 'A' + 10
  else -> null
}
