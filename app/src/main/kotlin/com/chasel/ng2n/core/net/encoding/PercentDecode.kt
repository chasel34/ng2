package com.chasel.ng2n.core.net.encoding

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
      else -> return null
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
    if (codePoint < minimum || codePoint > 0x10ffff) return null
    if (codePoint in 0xd800..0xdfff) return null
    out.appendCodePoint(codePoint)
  }
  return out.toString()
}

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
