package com.chasel.ng2n.core.net.encoding

import java.nio.charset.Charset

/**
 * GB18030(含 GBK 子集)的解码与出站编码。直译 `src/core/net/encoding/gb18030.ts`。
 *
 * ## 为什么不是一句 `String(bytes, Charset.forName("GB18030"))`
 *
 * 票 03 原话是「用 JVM `Charset.forName("GB18030")` 替代 RN 版手写状态机,但**策略照抄**」。
 * 2026-08-22 拿 JDK 17 的 GB18030 与 Node 的 `TextDecoder('gb18030')`(= WHATWG,
 * 也就是 RN 版逐序列对拍过的那个)整表对拍,**表几乎一样、取字节的框法不一样**:
 *
 * | | WHATWG / RN 版 | JDK 的 CharsetDecoder |
 * |---|---|---|
 * | `A3 A0`(全角空格) | U+3000 | U+E5E5(CP936 的老 PUA 映射) |
 * | 单独的 `0x80` | `€` | 非法字节 → U+FFFD |
 * | `81 30 41 42`(半截四字节) | `U+FFFD` + 把 `30 41 42` **退回流里**重解 | 一口气吞掉 3 字节 |
 * | `D4 7F`(ASCII 尾字节) | `U+FFFD` + 退回 `7F` | 吞掉 2 字节 |
 *
 * 头一条是**日常内容**(全角空格在中文帖子里到处都是),后三条决定坏字节处出多少个
 * U+FFFD——而 `decodeResponseBody` 未声明 charset 时正是**按 U+FFFD 个数投票**选编码的
 * (fid=414 那份坏字节抓包就卡在这)。所以框法必须照抄 WHATWG,不能交给 JDK。
 *
 * 于是这里的分工是:**框法自己走(照抄 TS 的状态机),映射表问 JDK 要**——
 * 76KB 的索引表就不必再抄一份进仓库了。整表对拍下来只需要两处补丁:
 *
 * 1. 双字节 pointer 6555(`A3 A0`):JDK 给 U+E5E5,WHATWG 给 U+3000 → [TWO_BYTE_PATCH];
 * 2. 四字节 BMP 段的 18 个 pointer:JDK 给 PUA,WHATWG 给真字符 → [FOUR_BYTE_PATCH]。
 *
 * 其余 23939 个双字节序列 + 1237558 个四字节序列 JDK 与 WHATWG 逐条相同(实测)。
 */

/** 解码失败时产出的 U+FFFD。 */
const val REPLACEMENT_CHAR: Char = '�'

private val GB18030: Charset = Charset.forName("GB18030")

/** WHATWG index gb18030 的双字节段大小。 */
private const val TWO_BYTE_POINTERS = 23940

/** 双字节段里 JDK 与 WHATWG 唯一的分歧:`A3 A0` = 全角空格。 */
private val TWO_BYTE_PATCH = mapOf(6555 to '　')

/**
 * 四字节 BMP 段里 JDK 与 WHATWG 的全部分歧(18 条)。
 *
 * JDK 的 GB18030-2000 表把这些码点留在双字节段,四字节槽位填了 PUA;
 * WHATWG 两处都给真字符。列表由整表对拍生成(2026-08-22)。
 */
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

/** 双字节 pointer → 码点的索引表,首次用到时问 JDK 要。 */
private val twoByteIndex: CharArray by lazy {
  CharArray(TWO_BYTE_POINTERS) { pointer ->
    TWO_BYTE_PATCH[pointer] ?: run {
      val decoded = String(byteArrayOf(leadByte(pointer), trailByte(pointer)), GB18030)
      if (decoded.length == 1) decoded[0] else REPLACEMENT_CHAR
    }
  }
}

/** 双字节 pointer → 码点;越界或无映射返回 -1。 */
private fun twoByteCodePoint(pointer: Int): Int {
  if (pointer < 0 || pointer >= TWO_BYTE_POINTERS) return -1
  val char = twoByteIndex[pointer]
  return if (char == REPLACEMENT_CHAR) -1 else char.code
}

/**
 * 四字节序列的 pointer → 码点;无映射返回 null。
 *
 * 边界判据照抄 WHATWG(也就是 TS 的 `rangesCodePoint`);星平面那一整段是算术映射,
 * 不必问表(emoji 走的就是这条,是热路径)。BMP 段问 JDK,再打 [FOUR_BYTE_PATCH]。
 */
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

/**
 * 按 WHATWG gb18030 解码器解码;非法序列产出 U+FFFD,**永不抛错**。
 *
 * 逐行对着 `gb18030.ts` 的状态机抄——尤其是两处「把字节退回流里重新解析」
 * (`i -= 2` / `i -= 1` / ASCII 尾字节不前进),它们决定坏数据处出几个 U+FFFD。
 */
fun decodeGb18030(bytes: ByteArray): String {
  val out = StringBuilder(bytes.size)
  var first = 0
  var second = 0
  var third = 0
  var i = 0

  while (true) {
    if (i >= bytes.size) {
      // 流末尾还留着未消费的前导字节 → 一个替换字符
      if (first != 0 || second != 0 || third != 0) out.append(REPLACEMENT_CHAR)
      break
    }
    val byte = bytes[i].toInt() and 0xff

    if (third != 0) {
      if (byte < 0x30 || byte > 0x39) {
        // 回退 second/third/byte 重新解析(second 在 i-2)
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
      // 回退 second/byte 重新解析(second 在 i-1)
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
      // ASCII 尾字节退回流里当普通字符重新解析
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

/**
 * 码点 → 双字节 pointer 的反查表,首次编码时才建。
 * 索引里有重复码点,取**第一个** pointer(与 WHATWG 的「index pointer」一致)。
 */
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

/** `encodeURIComponent` 不转义的那一档(JS 口径,与 `java.net.URLEncoder` 不同)。 */
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

/**
 * 按 GBK 编码后再 percent-encode,用于 NGA 那些吃 GBK 参数的接口
 * (`thread.php` 的 `author`、`forum.php` 的 `key`、`nuke.php` 的 `username` 等)。
 *
 * GBK 表里没有的字符(emoji 等)不丢弃,按 API 文档 §0.5 的转义约定写成
 * **UTF-16 码元的十进制 HTML 实体**——码点 > 0xFFFF 拆成代理对**两个**实体,
 * 例:`"😄"` → `&#55357;&#56836;`,实体本身再 percent 编码成
 * `%26%2355357%3B%26%2356836%3B`。
 *
 * 注意是**逐 UTF-16 码元**遍历而不是逐码点:表外字符本来就要按码元转实体。
 * 只查双字节表,**不走 GB18030 的四字节编码**——服务端那头是 GBK。
 */
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

/**
 * JS `encodeURIComponent` 的等价物(UTF-8 + 上面那张不转义表)。
 *
 * **不能用 `java.net.URLEncoder`**:它是 `application/x-www-form-urlencoded` 口径——
 * 空格变 `+`、`!~*'()` 一律转义,跟 RN 版逐字节对不上。
 *
 * 孤立代理码元直接抛错,与 JS 的 `URIError` 同语义:静默变成 `?` 送上去,
 * 是那种「服务端收到的内容莫名其妙少一个字」的难查 bug。
 */
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
