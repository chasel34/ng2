package com.chasel.ng2n.data.account

import com.chasel.ng2n.core.net.encoding.REPLACEMENT_CHAR
import com.chasel.ng2n.core.net.encoding.decodeGb18030

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
  val cid: String,
  val urlencodedUname: String?,
)

private val UID_PATTERN = Regex("^\\d+$")

private val CID_PATTERN = Regex("^[0-9A-Za-z_-]{16,}$")

fun extractLoginCookies(cookie: String): LoginCookies? {
  val jar = parseCookieString(cookie)
  val uid = jar[COOKIE_PASSPORT_UID_NAME] ?: ""
  val cid = jar[COOKIE_PASSPORT_CID_NAME] ?: ""
  if (!UID_PATTERN.matches(uid) || !CID_PATTERN.matches(cid)) return null
  return LoginCookies(uid = uid, cid = cid, urlencodedUname = jar[COOKIE_UNAME_NAME])
}

private const val COOKIE_PASSPORT_UID_NAME = "ngaPassportUid"
private const val COOKIE_PASSPORT_CID_NAME = "ngaPassportCid"
private const val COOKIE_UNAME_NAME = "ngaPassportUrlencodedUname"

fun decodeLoginUsername(raw: String): String? {
  val once = percentDecodeToBytes(raw) ?: return null
  val asText = buildString(once.size) { for (byte in once) append((byte.toInt() and 0xff).toChar()) }
  val twice = percentDecodeToBytes(asText) ?: return null
  val name = decodeGb18030(twice).trim()
  if (name == "" || name.contains(REPLACEMENT_CHAR)) return null
  return name
}

private fun percentDecodeToBytes(input: String): ByteArray? {
  val bytes = ArrayList<Byte>(input.length)
  var index = 0
  while (index < input.length) {
    val char = input[index]
    if (char == '%') {
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
