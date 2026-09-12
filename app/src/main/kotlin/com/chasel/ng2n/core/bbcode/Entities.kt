package com.chasel.ng2n.core.bbcode

import java.math.BigInteger

private val NAMED_ENTITIES: Map<String, String> = mapOf(
  "amp" to "&",
  "lt" to "<",
  "gt" to ">",
  "quot" to "\"",
  "apos" to "'",
  "nbsp" to "\u00a0",
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
      else -> NAMED_ENTITIES[name] ?: match.value
    }
  }

private fun fromCodeUnit(code: BigInteger): String {
  if (code.signum() < 0 || code > MAX_CODE_POINT) return ""
  val value = code.toInt()
  return if (value <= 0xffff) value.toChar().toString() else String(Character.toChars(value))
}

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
