package com.chasel.ng2n.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class NgaEnvelope(
  val root: JsonObject,
  val data: JsonElement?,
  val time: Long? = null,
  val fakeError: NgaServerError? = null,
)

enum class EnvelopeShape { WRAPPED, BARE }

private val EnvelopeJson = Json {
  isLenient = false
  ignoreUnknownKeys = false
  allowSpecialFloatingPointValues = false
}

fun parseNgaJson(
  text: String,
  via: String? = null,
  shape: EnvelopeShape = EnvelopeShape.WRAPPED,
): NgaEnvelope {
  val cleaned = sanitizeNgaJson(text)
  if (cleaned.isEmpty()) {
    throw NgaError(NgaErrorKind.PARSE, "响应为空", via = via)
  }

  val root: JsonElement = try {
    EnvelopeJson.parseToJsonElement(cleaned).also { requireJsonParseCompatibleRoot(it) }
  } catch (cause: Exception) {
    throw NgaError(
      NgaErrorKind.PARSE,
      "响应不是合法 JSON：${cleaned.take(PREVIEW_LENGTH)}",
      via = via,
      cause = cause,
    )
  }

  if (root !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "响应顶层不是对象", via = via)
  }

  val serverError = extractServerError(root)
  if (serverError != null && !isFakeError(serverError.message)) {
    throw NgaError(
      NgaErrorKind.SERVER,
      serverError.message,
      code = serverError.code,
      via = via,
    )
  }

  val time = timeOf(root["time"])
  val shelled = root.containsKey("data") || root.containsKey("error")
  if (!shelled && shape == EnvelopeShape.WRAPPED) {
    throw NgaError(
      NgaErrorKind.PARSE,
      "响应顶层既没有 data 也没有 error：${cleaned.take(PREVIEW_LENGTH)}",
      via = via,
    )
  }

  return NgaEnvelope(
    root = root,
    data = if (shelled) root["data"] else root,
    time = time,
    fakeError = serverError,
  )
}

private const val PREVIEW_LENGTH = 120

private val JSON_NUMBER = Regex("""^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$""")

private fun requireJsonParseCompatibleRoot(root: JsonElement) {
  if (root !is JsonPrimitive || root.isString) return
  val content = root.content
  if (content == "true" || content == "false" || content == "null") return
  require(JSON_NUMBER.matches(content)) { "顶层不是合法的 JSON 取值:$content" }
}

private fun timeOf(raw: JsonElement?): Long? {
  if (raw !is JsonPrimitive || raw.isString) return null
  val value = raw.content.toDoubleOrNull() ?: return null
  return if (value.isFinite()) jsTrunc(value) else null
}
