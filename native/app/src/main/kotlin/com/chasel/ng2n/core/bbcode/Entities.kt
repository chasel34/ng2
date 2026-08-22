package com.chasel.ng2n.core.bbcode

import java.math.BigInteger

/**
 * NGA 文本的转义与反转义(读、写各一个方向)。直译 `src/core/bbcode/entities.ts`。
 *
 * NGA 会对正文做**双重** HTML 转义,并把所有码点 > 0xFFFF 的字符(emoji 等)
 * 拆成两个 UTF-16 码元的十进制实体。所以读取时要跑两轮实体解码,例:
 * `&amp;#55357;&amp;#56836;` → `&#55357;&#56836;` → `😄`。
 *
 * 双重解码是**逆向怪癖,勿修**(ADR-0002)。
 */

private val NAMED_ENTITIES: Map<String, String> = mapOf(
  "amp" to "&",
  "lt" to "<",
  "gt" to ">",
  "quot" to "\"",
  "apos" to "'",
  "nbsp" to "\u00a0", // NBSP,不是普通空格——NGA 靠它保排版
)

private val ENTITY_PATTERN = Regex("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z][a-zA-Z0-9]*));")

private val MAX_CODE_POINT = BigInteger.valueOf(0x10ffff)

fun unescapeNgaText(raw: String): String = dropLoneSurrogates(decodeHtmlEntities(decodeHtmlEntities(raw)))

private fun decodeHtmlEntities(input: String): String =
  ENTITY_PATTERN.replace(input) { match ->
    val decimal = match.groups[1]?.value
    val hex = match.groups[2]?.value
    val name = match.groups[3]?.value
    when {
      decimal != null -> fromCodeUnit(BigInteger(decimal, 10))
      hex != null -> fromCodeUnit(BigInteger(hex, 16))
      // 不认识的具名实体原样留着(TS 侧返回 match)
      else -> NAMED_ENTITIES[name] ?: match.value
    }
  }

/**
 * NGA 把星平面字符拆成两个 UTF-16 码元实体,所以这里按**码元**而非码点还原:
 * 相邻的高低代理码元拼接后天然组成一个星平面字符。
 *
 * 越界(> 0x10FFFF)落成空串,与 TS 的 `fromCodeUnit` 一致——JS 的 `parseInt`
 * 对超长数字串返回一个有限但巨大的浮点数,照样落进「越界」这一档,所以这里用
 * `BigInteger` 而不是 `toInt()`(会溢出成小数字,反而解出一个字符来)。
 */
private fun fromCodeUnit(code: BigInteger): String {
  if (code.signum() < 0 || code > MAX_CODE_POINT) return ""
  val value = code.toInt()
  // ≤0xFFFF 直接当码元用:这里可能造出孤立代理,交给 dropLoneSurrogates 收尾
  return if (value <= 0xffff) value.toChar().toString() else String(Character.toChars(value))
}

/**
 * 孤立代理码元 → U+FFFD。等价于 TS 的
 * `/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g`,
 * 手写扫描比正则在 JVM 上更好推理(Java 正则按码点走,行为对得上但不显然)。
 */
private fun dropLoneSurrogates(input: String): String {
  var index = 0
  while (index < input.length) {
    val char = input[index]
    if (char.isHighSurrogate() || char.isLowSurrogate()) break
    index++
  }
  if (index == input.length) return input

  val out = StringBuilder(input.length)
  out.append(input, 0, index)
  while (index < input.length) {
    val char = input[index]
    when {
      char.isHighSurrogate() && index + 1 < input.length && input[index + 1].isLowSurrogate() -> {
        out.append(char).append(input[index + 1])
        index += 2
      }
      char.isHighSurrogate() || char.isLowSurrogate() -> {
        out.append('�')
        index++
      }
      else -> {
        out.append(char)
        index++
      }
    }
  }
  return out.toString()
}

/**
 * 提交侧转义(API 文档 §13 第 4 条):不转的话旧接口会拒收或存成乱码。
 *
 * 要转成 UTF-16 码元十进制实体的是这几档:
 *
 * | 范围 | 说明 |
 * |---|---|
 * | 码点 > `0xFFFF` | emoji 等星平面字符,拆成代理对**两个**实体 |
 * | `0x200D` | 零宽连接符(家庭 emoji 这类 ZWJ 序列靠它连起来) |
 * | `0x2600`–`0x27BF` | 杂项符号与装饰符(`❤` 在这一档) |
 * | `0xFE00`–`0xFE0F` | 变体选择符(`❤️` 后面那个强制 emoji 呈现的码元) |
 *
 * 其余字符(含中文)原样留着——参数编码由 core/net 那层按接口定夺。
 */
fun escapeForSubmit(text: String): String {
  val out = StringBuilder(text.length)
  var index = 0
  while (index < text.length) {
    val code = text.codePointAt(index)
    val width = Character.charCount(code)
    when {
      code > 0xffff -> out.append("&#${text[index].code};&#${text[index + 1].code};")
      needsSubmitEscape(code) -> out.append("&#$code;")
      else -> out.append(text, index, index + width)
    }
    index += width
  }
  return out.toString()
}

private fun needsSubmitEscape(code: Int): Boolean =
  code == 0x200d || (code in 0x2600..0x27bf) || (code in 0xfe00..0xfe0f)
