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

data class GoldenCase(
  val domain: String,
  val name: String,
  val fn: String,
  val input: JsonElement,
  val inputEncoding: String?,
  val expected: JsonElement,
  val note: String?,
) {
  val resourcePath: String get() = "goldens/$domain/$name.json"

  val expectsThrow: Boolean
    get() = expected is JsonObject && expected.size == 1 && expected.containsKey("throws")

  fun inputObject(): JsonObject = input as? JsonObject
    ?: fail("input 不是对象,而是 ${input::class.simpleName}")

  fun inputString(): String = input.asStringOrNull()
    ?: fail("input 不是字符串,而是 $input")

  fun inputStringOrNull(): String? = if (input is JsonNull) null else inputString()

  fun field(key: String): JsonElement = inputObject()[key]
    ?: fail("input 缺 `$key` 字段")

  fun stringField(key: String): String = field(key).asStringOrNull()
    ?: fail("input.$key 不是字符串,而是 ${field(key)}")

  fun stringFieldOrNull(key: String): String? {
    val value = inputObject()[key] ?: return null
    if (value is JsonNull) return null
    return value.asStringOrNull() ?: fail("input.$key 不是字符串,而是 $value")
  }

  fun booleanField(key: String): Boolean = runCatching { field(key).jsonPrimitive.boolean }
    .getOrElse { fail("input.$key 不是布尔,而是 ${field(key)}") }

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

internal fun JsonElement.asStringOrNull(): String? {
  val primitive = this as? JsonPrimitive ?: return null
  return if (primitive.isString) primitive.content else null
}

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
      char.isSurrogate() || char.code == 0xfffd -> out.append("\\u%04x".format(char.code))
      else -> out.append(char)
    }
  }
  out.append('"')
  return out.toString()
}
