package com.chasel.ng2n.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * 一条金样本(`src/test/resources/goldens/<domain>/<case>.json`)。
 *
 * schema 是 05a 定的,逐字段见 `goldens/README.md`「文件 schema」一节:
 * `expected` / `fn` / `input` / `inputEncoding?` / `name` / `note?`。
 * 这里只做「读进来」,不做任何解释——解释在各 domain 的对拍测试里。
 */
data class GoldenCase(
  val domain: String,
  val name: String,
  /** 被测的 TS 函数名。一个 domain 覆盖多个函数是常态,参数化跑法按它分派。 */
  val fn: String,
  val input: JsonElement,
  /** 只在 `input.bytes` 是 base64 原始响应字节时出现(目前只有 `decode-body`)。 */
  val inputEncoding: String?,
  val expected: JsonElement,
  val note: String?,
) {
  /** classpath 上的资源路径,报错时指路用。 */
  val resourcePath: String get() = "goldens/$domain/$name.json"

  /**
   * `expected` 是个恰好只有 `throws` 一个键的对象 ⇒ 这条期望抛错。
   * README 已确认本批语料里没有任何函数会正常返回带 `throws` 键的对象,不存在歧义。
   */
  val expectsThrow: Boolean
    get() = expected is JsonObject && expected.size == 1 && expected.containsKey("throws")

  // --- input 取值助手(形状按 fn 定,取错了直接抛,不静默降级) -------------------

  fun inputObject(): JsonObject = input as? JsonObject
    ?: fail("input 不是对象,而是 ${input::class.simpleName}")

  fun inputString(): String = input.asStringOrNull()
    ?: fail("input 不是字符串,而是 $input")

  /** `input` 整体可能是 `null`(TS 侧传了 `undefined`)。 */
  fun inputStringOrNull(): String? = if (input is JsonNull) null else inputString()

  fun field(key: String): JsonElement = inputObject()[key]
    ?: fail("input 缺 `$key` 字段")

  fun stringField(key: String): String = field(key).asStringOrNull()
    ?: fail("input.$key 不是字符串,而是 ${field(key)}")

  /** `null` 与「缺键」都返回 null——只在 TS 侧本来就允许 `string | null | undefined` 的字段上用。 */
  fun stringFieldOrNull(key: String): String? {
    val value = inputObject()[key] ?: return null
    if (value is JsonNull) return null
    return value.asStringOrNull() ?: fail("input.$key 不是字符串,而是 $value")
  }

  fun booleanField(key: String): Boolean = runCatching { field(key).jsonPrimitive.boolean }
    .getOrElse { fail("input.$key 不是布尔,而是 ${field(key)}") }

  /** `inputEncoding: "base64"` 的原始响应字节(固定在 `input.bytes`)。 */
  fun bytesField(key: String = "bytes"): ByteArray {
    check(inputEncoding == "base64") {
      "$resourcePath 没声明 inputEncoding=base64,不该按字节读 `$key`"
    }
    return Base64.getDecoder().decode(stringField(key))
  }

  private fun fail(reason: String): Nothing = throw AssertionError("$resourcePath: $reason")

  companion object {
    fun from(domain: String, element: JsonElement): GoldenCase {
      val obj = element.jsonObject
      val name = obj.getValue("name").jsonPrimitive.content
      return GoldenCase(
        domain = domain,
        name = name,
        fn = obj.getValue("fn").jsonPrimitive.content,
        input = obj.getValue("input"),
        inputEncoding = (obj["inputEncoding"] as? JsonPrimitive)?.content,
        expected = obj.getValue("expected"),
        note = (obj["note"] as? JsonPrimitive)?.content,
      )
    }
  }
}

/** 字符串原语 → String;其余(数字/布尔/null/对象/数组)返回 null。 */
internal fun JsonElement.asStringOrNull(): String? {
  val primitive = this as? JsonPrimitive ?: return null
  return if (primitive.isString) primitive.content else null
}

/** 只在 diff 里当「短标签」用,不参与比较。 */
internal fun JsonElement.preview(limit: Int = 160): String {
  val raw = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) quote(content) else content
    is JsonArray -> "[${size} 项]"
    is JsonObject -> "{${keys.joinToString(", ")}}"
  }
  return if (raw.length <= limit) raw else raw.take(limit) + "…"
}

internal fun quote(text: String): String {
  val out = StringBuilder(text.length + 2)
  out.append('"')
  for (char in text) {
    when {
      char == '"' -> out.append("\\\"")
      char == '\\' -> out.append("\\\\")
      char == '\n' -> out.append("\\n")
      char == '\r' -> out.append("\\r")
      char == '\t' -> out.append("\\t")
      char.code < 0x20 || char.code == 0x7f -> out.append("\\u%04x".format(char.code))
      // 代理码元与 U+FFFD 单独放出来:GBK 对拍出问题时肉眼看不出它们
      char.isSurrogate() || char.code == 0xfffd -> out.append("\\u%04x".format(char.code))
      else -> out.append(char)
    }
  }
  out.append('"')
  return out.toString()
}
