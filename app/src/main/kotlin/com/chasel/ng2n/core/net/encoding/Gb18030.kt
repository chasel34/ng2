package com.chasel.ng2n.core.net.encoding

import java.nio.charset.Charset

const val REPLACEMENT_CHAR: Char = '�'

private val GB18030: Charset = Charset.forName("GB18030")

private const val TWO_BYTE_POINTERS = 23940

// JDK 将 A3 A0 映射到私用区；WHATWG 规定为全角空格。
private val TWO_BYTE_PATCH = mapOf(6555 to '　')

// 将 JDK GB18030-2000 的四字节私用区映射修正为 WHATWG 字符。
private val FOUR_BYTE_PATCH: Map<Int, Int> = mapOf(
  19057 to 0x9fb4, 19058 to 0x9fb5, 19059 to 0x9fb6, 19060 to 0x9fb7,
  19061 to 0x9fb8, 19062 to 0x9fb9, 19063 to 0x9fba, 19064 to 0x9fbb,
  39076 to 0xfe10, 39077 to 0xfe11, 39078 to 0xfe12, 39079 to 0xfe13,
  39080 to 0xfe14, 39081 to 0xfe15, 39082 to 0xfe16, 39083 to 0xfe17,
  39084 to 0xfe18, 39085 to 0xfe19,
)

private fun leadByte(pointer: Int): Byte = (pointer / 190 + 0x81).toByte()

private fun trailByte(pointer: Int): Byte {
  val trailIndex = pointer % 190
  return (if (trailIndex < 0x3f) trailIndex + 0x40 else trailIndex + 0x41).toByte()
}

private val twoByteIndex: CharArray by lazy {
  CharArray(TWO_BYTE_POINTERS) { pointer ->
    TWO_BYTE_PATCH[pointer] ?: run {
      val decoded = String(byteArrayOf(leadByte(pointer), trailByte(pointer)), GB18030)
      if (decoded.length == 1) decoded[0] else REPLACEMENT_CHAR
    }
  }
}

private fun twoByteCodePoint(pointer: Int): Int {
  if (pointer < 0 || pointer >= TWO_BYTE_POINTERS) return -1
  val char = twoByteIndex[pointer]
  return if (char == REPLACEMENT_CHAR) -1 else char.code
}

private fun rangesCodePoint(pointer: Int): Int? {
  if ((pointer > 39419 && pointer < 189000) || pointer > 1237575) return null
  if (pointer >= 189000) return pointer - 189000 + 0x10000
  FOUR_BYTE_PATCH[pointer]?.let { return it }
  val rest1 = pointer % 12600
  val rest2 = rest1 % 1260
  val bytes = byteArrayOf(
    (pointer / 12600 + 0x81).toByte(),
    (rest1 / 1260 + 0x30).toByte(),
    (rest2 / 10 + 0x81).toByte(),
    (rest2 % 10 + 0x30).toByte(),
  )
  val decoded = String(bytes, GB18030)
  if (decoded.length != 1 || decoded[0] == REPLACEMENT_CHAR) return null
  return decoded[0].code
}

/** 按 WHATWG 回退非法序列，保持替换字符数量与编码探测规则一致。 */
fun decodeGb18030(bytes: ByteArray): String {
  val out = StringBuilder(bytes.size)
  var first = 0
  var second = 0
  var third = 0
  var i = 0

  while (true) {
    if (i >= bytes.size) {
      if (first != 0 || second != 0 || third != 0) out.append(REPLACEMENT_CHAR)
      break
    }
    val byte = bytes[i].toInt() and 0xff

    if (third != 0) {
      if (byte < 0x30 || byte > 0x39) {
        i -= 2
        first = 0
        second = 0
        third = 0
        out.append(REPLACEMENT_CHAR)
        continue
      }
      val pointer = (first - 0x81) * 12600 +
        (second - 0x30) * 1260 +
        (third - 0x81) * 10 +
        (byte - 0x30)
      first = 0
      second = 0
      third = 0
      i += 1
      val codePoint = rangesCodePoint(pointer)
      if (codePoint == null) out.append(REPLACEMENT_CHAR) else out.appendCodePoint(codePoint)
      continue
    }

    if (second != 0) {
      if (byte in 0x81..0xfe) {
        third = byte
        i += 1
        continue
      }
      i -= 1
      first = 0
      second = 0
      out.append(REPLACEMENT_CHAR)
      continue
    }

    if (first != 0) {
      if (byte in 0x30..0x39) {
        second = byte
        i += 1
        continue
      }
      val lead = first
      first = 0
      var codePoint = -1
      if (byte in 0x40..0x7e || byte in 0x80..0xfe) {
        val offset = if (byte < 0x7f) 0x40 else 0x41
        codePoint = twoByteCodePoint((lead - 0x81) * 190 + (byte - offset))
      }
      if (codePoint >= 0) {
        out.append(codePoint.toChar())
        i += 1
        continue
      }
      out.append(REPLACEMENT_CHAR)
      if (byte > 0x7f) i += 1
      continue
    }

    if (byte <= 0x7f) {
      out.append(byte.toChar())
      i += 1
      continue
    }
    if (byte == 0x80) {
      out.append('€')
      i += 1
      continue
    }
    if (byte <= 0xfe) {
      first = byte
      i += 1
      continue
    }
    out.append(REPLACEMENT_CHAR)
    i += 1
  }

  return out.toString()
}

private val gbkEncodeIndex: Map<Char, Int> by lazy {
  val map = HashMap<Char, Int>(TWO_BYTE_POINTERS * 2)
  val index = twoByteIndex
  for (pointer in index.indices) {
    val char = index[pointer]
    if (char == REPLACEMENT_CHAR) continue
    map.putIfAbsent(char, pointer)
  }
  map
}

private fun isUriUnreserved(code: Int): Boolean =
  code in 'A'.code..'Z'.code ||
    code in 'a'.code..'z'.code ||
    code in '0'.code..'9'.code ||
    code == '-'.code || code == '_'.code || code == '.'.code || code == '!'.code ||
    code == '~'.code || code == '*'.code || code == '\''.code ||
    code == '('.code || code == ')'.code

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun StringBuilder.appendPercent(byte: Int) {
  append('%')
  append(HEX_DIGITS[(byte shr 4) and 0xf])
  append(HEX_DIGITS[byte and 0xf])
}

private fun StringBuilder.appendAscii(code: Int) {
  if (isUriUnreserved(code)) append(code.toChar()) else appendPercent(code)
}

fun gbkEncodeUriComponent(text: String): String {
  val index = gbkEncodeIndex
  val out = StringBuilder(text.length * 3)
  for (char in text) {
    val unit = char.code
    if (unit <= 0x7f) {
      out.appendAscii(unit)
      continue
    }
    val pointer = index[char]
    if (pointer == null) {
      for (entityChar in "&#$unit;") out.appendAscii(entityChar.code)
      continue
    }
    out.appendPercent(leadByte(pointer).toInt() and 0xff)
    out.appendPercent(trailByte(pointer).toInt() and 0xff)
  }
  return out.toString()
}

fun encodeUriComponent(text: String): String {
  val out = StringBuilder(text.length * 3)
  var index = 0
  while (index < text.length) {
    val code = text.codePointAt(index)
    index += Character.charCount(code)
    if (isUriUnreserved(code)) {
      out.append(code.toChar())
      continue
    }
    require(code !in 0xd800..0xdfff) { "encodeURIComponent:孤立的代理码元 U+%04X".format(code) }
    when {
      code <= 0x7f -> out.appendPercent(code)
      code <= 0x7ff -> {
        out.appendPercent(0xc0 or (code shr 6))
        out.appendPercent(0x80 or (code and 0x3f))
      }
      code <= 0xffff -> {
        out.appendPercent(0xe0 or (code shr 12))
        out.appendPercent(0x80 or ((code shr 6) and 0x3f))
        out.appendPercent(0x80 or (code and 0x3f))
      }
      else -> {
        out.appendPercent(0xf0 or (code shr 18))
        out.appendPercent(0x80 or ((code shr 12) and 0x3f))
        out.appendPercent(0x80 or ((code shr 6) and 0x3f))
        out.appendPercent(0x80 or (code and 0x3f))
      }
    }
  }
  return out.toString()
}
