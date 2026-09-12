package com.chasel.ng2n.core.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val RED = 1
private const val BLUE = 2
private const val GREEN = 4
private const val ORANGE = 8
private const val SILVER = 16
private const val BOLD = 32
private const val ITALIC = 64
private const val UNDERLINE = 128

private const val TLV_MASK = 1
private const val TLV_STID = 2
private const val TLV_SFID = 3

private const val TLV_RECORD_SIZE = 5

@Serializable
enum class TitleColor {
  @SerialName("red")
  RED,

  @SerialName("blue")
  BLUE,

  @SerialName("green")
  GREEN,

  @SerialName("orange")
  ORANGE,

  @SerialName("silver")
  SILVER,
}

@Serializable
data class TitleStyle(
  val color: TitleColor? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
)

val PLAIN_TITLE_STYLE = TitleStyle()

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private val TILDE_SUFFIX = Regex("~1?$")

private fun decodeBase64(value: String): ByteArray? {
  val body = value.trimEnd('=')
  val bytes = ByteArray(body.length * 3 / 4)
  var accumulator = 0
  var bits = 0
  var length = 0

  for (character in body) {
    val index = BASE64_ALPHABET.indexOf(character)
    if (index < 0) return null
    accumulator = (accumulator shl 6) or index
    bits += 6
    if (bits >= 8) {
      bits -= 8
      bytes[length++] = ((accumulator shr bits) and 0xff).toByte()
    }
  }

  return bytes.copyOf(length)
}

data class TopicMisc(
  val mask: Long? = null,
  val stid: Long? = null,
  val sfid: Long? = null,
)

private const val U32 = 0x1_0000_0000L
private const val INT32_MAX = 0x7fff_ffffL

fun signedBoardId(value: Long?): Long? {
  if (value == null) return null
  return if (value > INT32_MAX && value < U32) value - U32 else value
}

fun parseTopicMisc(raw: Any?): TopicMisc {
  if (raw !is String || raw.isEmpty() || TILDE_SUFFIX.containsMatchIn(raw)) return TopicMisc()
  val bytes = decodeBase64(raw) ?: return TopicMisc()

  var mask: Long? = null
  var stid: Long? = null
  var sfid: Long? = null

  var at = 0
  while (at + TLV_RECORD_SIZE <= bytes.size) {
    val type = bytes[at].toInt() and 0xff
    if (type == 0) break
    val value =
      ((bytes[at + 1].toLong() and 0xff) shl 24) +
        ((bytes[at + 2].toLong() and 0xff) shl 16) +
        ((bytes[at + 3].toLong() and 0xff) shl 8) +
        (bytes[at + 4].toLong() and 0xff)

    when (type) {
      TLV_MASK -> mask = value
      TLV_STID -> stid = value
      TLV_SFID -> sfid = signedBoardId(value)
    }
    at += TLV_RECORD_SIZE
  }
  return TopicMisc(mask, stid, sfid)
}

private fun toMask(raw: Any?): Double? {
  if (raw is Number) {
    val value = raw.toDouble()
    return if (value.isFinite()) value else null
  }
  if (raw !is String || raw.jsTrim().isEmpty()) return null
  val parsed = jsNumber(raw)
  return if (parsed.isFinite()) parsed else null
}

private fun toInt32(value: Double): Int {
  if (!value.isFinite()) return 0
  return value.toLong().toInt()
}

fun titleStyleFromMask(mask: Long): TitleStyle {
  val bits = mask.toInt()
  val color = when {
    bits and RED != 0 -> TitleColor.RED
    bits and BLUE != 0 -> TitleColor.BLUE
    bits and GREEN != 0 -> TitleColor.GREEN
    bits and ORANGE != 0 -> TitleColor.ORANGE
    bits and SILVER != 0 -> TitleColor.SILVER
    else -> null
  }
  return TitleStyle(
    color = color,
    bold = bits and BOLD != 0,
    italic = bits and ITALIC != 0,
    underline = bits and UNDERLINE != 0,
  )
}

fun decodeTitleStyle(titlefont: Any? = null, topicMisc: Any? = null): TitleStyle {
  val mask = parseTopicMisc(topicMisc).mask?.toDouble() ?: toMask(titlefont)
  return if (mask == null || mask == 0.0) PLAIN_TITLE_STYLE else titleStyleFromMask(toInt32(mask).toLong())
}
