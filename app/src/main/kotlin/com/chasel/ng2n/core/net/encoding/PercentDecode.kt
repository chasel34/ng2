package com.chasel.ng2n.core.net.encoding

/**
 * JS `decodeURIComponent` 的等价物(票 10 加:深链的 query 值、附件文件名都要它)。
 *
 * **不能用 `java.net.URLDecoder`**:它是 `application/x-www-form-urlencoded` 口径——
 * 把裸 `+` 解成空格、遇上坏转义抛 `IllegalArgumentException` 的时机也不一样。
 * (需要「`+` → 空格」的地方由调用方自己先替换,TS 侧就是这么写的。)
 *
 * 与 JS 一致的严格之处:`%` 后必须是两位十六进制;多字节序列的首字节决定长度、
 * 后续字节必须是 `10xxxxxx`;拒绝过长编码(overlong)、代理区码点与 > U+10FFFF。
 * 任一条不满足,JS 抛 `URIError`——这里返回 `null`,调用点照 TS 的 `try/catch`
 * 决定「原样保留」还是别的降级。
 */
fun decodeUriComponentOrNull(value: String): String? {
  if ('%' !in value) return value

  val out = StringBuilder(value.length)
  var index = 0
  while (index < value.length) {
    val char = value[index]
    if (char != '%') {
      out.append(char)
      index++
      continue
    }

    val first = hexByteAt(value, index) ?: return null
    index += 3
    if (first < 0x80) {
      out.append(first.toChar())
      continue
    }

    val extra = when {
      first and 0xe0 == 0xc0 -> 1
      first and 0xf0 == 0xe0 -> 2
      first and 0xf8 == 0xf0 -> 3
      else -> return null // 10xxxxxx 打头 / 11111xxx 都不是合法首字节
    }
    var codePoint = first and (0x7f shr (extra + 1))
    repeat(extra) {
      val continuation = hexByteAt(value, index) ?: return null
      if (continuation and 0xc0 != 0x80) return null
      codePoint = (codePoint shl 6) or (continuation and 0x3f)
      index += 3
    }

    val minimum = when (extra) {
      1 -> 0x80
      2 -> 0x800
      else -> 0x10000
    }
    // overlong、代理区、越界:JS 这三档都抛 URIError
    if (codePoint < minimum || codePoint > 0x10ffff) return null
    if (codePoint in 0xd800..0xdfff) return null
    out.appendCodePoint(codePoint)
  }
  return out.toString()
}

/** `value[at]` 是 `%` 时读它后面那两位十六进制;形状不对返回 `null`。 */
private fun hexByteAt(value: String, at: Int): Int? {
  if (at + 2 >= value.length || value[at] != '%') return null
  val high = hexDigit(value[at + 1]) ?: return null
  val low = hexDigit(value[at + 2]) ?: return null
  return (high shl 4) or low
}

private fun hexDigit(char: Char): Int? = when (char) {
  in '0'..'9' -> char - '0'
  in 'a'..'f' -> char - 'a' + 10
  in 'A'..'F' -> char - 'A' + 10
  else -> null
}
