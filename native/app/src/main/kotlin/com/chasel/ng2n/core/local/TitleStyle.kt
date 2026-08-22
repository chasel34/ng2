package com.chasel.ng2n.core.local

/**
 * 彩色标题:把 `titlefont` / `topic_misc` 解成标题样式。直译 `src/core/local/title-style.ts`。
 *
 * 两个来源、同一套掩码(API 文档 §2 解析要点 4):
 * - `titlefont` 直接就是掩码(老字段,可能是数字也可能是数字字符串)
 * - `topic_misc` 是 base64(**无 padding**)的 TLV 串,`type=1` 的那条才是掩码,
 *   且**优先于 titlefont**——它是服务端现在实际在发的字段,`titlefont` 常年是空串。
 *
 * 掩码位与「颜色只取第一个」的优先级照 NGA 官方前端 `commonui.topicMiscVar`
 * 与主题列表渲染那段 else-if 链(js_commonui.js)。
 */

/** 掩码位。1/2/4/8/16 是颜色(互斥取第一个),32/64/128 是字形(可叠加)。 */
private const val RED = 1
private const val BLUE = 2
private const val GREEN = 4
private const val ORANGE = 8
private const val SILVER = 16
private const val BOLD = 32
private const val ITALIC = 64
private const val UNDERLINE = 128

/** TLV 的 type:1 = 字体掩码,2 = 合集 stid,3 = 子版块 fid。 */
private const val TLV_MASK = 1
private const val TLV_STID = 2
private const val TLV_SFID = 3

/** 每条记录固定 5 字节:1 字节 type + 4 字节大端无符号整数。 */
private const val TLV_RECORD_SIZE = 5

enum class TitleColor { RED, BLUE, GREEN, ORANGE, SILVER }

data class TitleStyle(
  /** 没上色时是 `null`,UI 用正文色 */
  val color: TitleColor? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
)

/** 没有任何样式的普通标题。 */
val PLAIN_TITLE_STYLE = TitleStyle()

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** 官方对以 `~` / `~1` 结尾的 `topic_misc` 直接返回空。 */
private val TILDE_SUFFIX = Regex("~1?$")

/**
 * base64 解码。**不用 `java.util.Base64`**:NGA 发的是**无 padding** 变体,
 * 末尾不足一字节的余位直接丢,而 JDK 的解码器对残缺尾组的口径与 TS 版那段手写循环
 * 不一定一致;含非法字符返回 `null`——上层当这个字段没写。
 */
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
  /** `titlefont` 那套字体掩码 */
  val mask: Long? = null,
  /** 合集 id */
  val stid: Long? = null,
  /** 子版块 fid(版块镜像行要靠它跳转) */
  val sfid: Long? = null,
)

private const val U32 = 0x1_0000_0000L
private const val INT32_MAX = 0x7fff_ffffL

/**
 * 把「无符号 32 位形态的版块 ID」还原成负数。
 *
 * **NGA 的版块 ID 可以是负数**:`-7` 网事杂谈这类特殊版面,以及个人版面
 * (实测 fid=-7955747 / -608808 / -522474 …)。JSON 里服务端老老实实发负号,
 * 但 `topic_misc` 那串 TLV 是 4 字节大端**裸整数**,按无符号读出来 `-8725919`
 * 就变成 `4286241377`,跳转过去服务端回「56:版面ID4286241377不存在」
 * (2026-08-13 走查实测,消费电子版「小窗视界 [版面镜像]」)。
 *
 * ⚠️ **只能用在 `fid`/`sfid` 上**。`stid`(合集)与 `tid` 都是**主题 id**——合集本身
 * 就是一个主题,主题 id 是无上限的正整数(现在四千七百万量级),将来越过 2^31
 * 时套上这条规则会被平白改成负数。版块 fid 的量级在十万级,落进 (2^31, 2^32)
 * 只可能是符号丢了。
 */
fun signedBoardId(value: Long?): Long? {
  if (value == null) return null
  return if (value > INT32_MAX && value < U32) value - U32 else value
}

/**
 * 解 `topic_misc`。解不开、空、或以 `~` / `~1` 结尾(官方在这里直接返回空)都给空对象——
 * 这个字段坏掉不该连累整条主题。
 *
 * `raw` 收 `Any?` 是照抄 TS 的 `unknown`:服务端这个字段偶尔发数字、偶尔缺席。
 */
fun parseTopicMisc(raw: Any?): TopicMisc {
  if (raw !is String || raw.isEmpty() || TILDE_SUFFIX.containsMatchIn(raw)) return TopicMisc()
  val bytes = decodeBase64(raw) ?: return TopicMisc()

  var mask: Long? = null
  var stid: Long? = null
  var sfid: Long? = null

  var at = 0
  while (at + TLV_RECORD_SIZE <= bytes.size) {
    val type = bytes[at].toInt() and 0xff
    // type=0 是串尾(官方那个 while 条件就是靠它跳出)
    if (type == 0) break
    val value =
      ((bytes[at + 1].toLong() and 0xff) shl 24) +
        ((bytes[at + 2].toLong() and 0xff) shl 16) +
        ((bytes[at + 3].toLong() and 0xff) shl 8) +
        (bytes[at + 4].toLong() and 0xff)

    // 掩码是位字段、stid 是主题 id,两者都按无符号读;
    // 只有 sfid 是版块 id,可能是负数(见 signedBoardId)
    when (type) {
      TLV_MASK -> mask = value
      TLV_STID -> stid = value
      TLV_SFID -> sfid = signedBoardId(value)
      // 未知 type 照样按 5 字节跳过,官方也这么处理
    }
    at += TLV_RECORD_SIZE
  }
  return TopicMisc(mask, stid, sfid)
}

/** `titlefont` 偶尔是数字字符串、偶尔是空串、偶尔干脆是数字。 */
private fun toMask(raw: Any?): Double? {
  if (raw is Number) {
    val value = raw.toDouble()
    return if (value.isFinite()) value else null
  }
  if (raw !is String || raw.jsTrim().isEmpty()) return null
  val parsed = jsNumber(raw)
  return if (parsed.isFinite()) parsed else null
}

/** JS 的 `ToInt32`:位运算前把数字截成 32 位有符号。掩码里的高位靠它折回来。 */
private fun toInt32(value: Double): Int {
  if (!value.isFinite()) return 0
  return value.toLong().toInt()
}

/** 掩码 → 样式。颜色位可能同时点亮,官方按 红>蓝>绿>橙>银 只取第一个。 */
fun titleStyleFromMask(mask: Long): TitleStyle {
  // JS 的位运算先 ToInt32,掩码高位为 1 时那是个负数——`and` 的结果一样,照抄
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

/**
 * 解标题样式。两个来源都在时以 `topic_misc` 为准。
 *
 * 两个入参收 `Any?` 是照抄 TS 的 `unknown`(服务端同一字段数字/字符串/缺席都出现过)。
 */
fun decodeTitleStyle(titlefont: Any? = null, topicMisc: Any? = null): TitleStyle {
  val mask = parseTopicMisc(topicMisc).mask?.toDouble() ?: toMask(titlefont)
  return if (mask == null || mask == 0.0) PLAIN_TITLE_STYLE else titleStyleFromMask(toInt32(mask).toLong())
}
