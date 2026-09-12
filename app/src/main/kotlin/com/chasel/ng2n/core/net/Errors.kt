package com.chasel.ng2n.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class NgaErrorKind(
  val wire: String,
) {
  NETWORK("network"),

  HTTP("http"),

  PARSE("parse"),

  SERVER("server"),

  UNAVAILABLE("unavailable"),
}

private fun defaultRetryable(kind: NgaErrorKind): Boolean = kind != NgaErrorKind.SERVER

class NgaError(
  val kind: NgaErrorKind,
  message: String,
  val code: JsonPrimitive? = null,
  val status: Int? = null,
  val via: String? = null,
  cause: Throwable? = null,
  retryable: Boolean? = null,
) : Exception(message, cause) {

  val retryable: Boolean = retryable ?: defaultRetryable(kind)

  var diagnostic: FetchDiagnostic? = null

  val text: String get() = message ?: ""

  override fun toString(): String =
    "NgaError(kind=${kind.wire}, retryable=$retryable, message=$text)"
}

data class NgaServerError(val code: JsonPrimitive, val message: String)

val UNKNOWN_SERVER_CODE: JsonPrimitive = JsonPrimitive("?")

val FAKE_ERROR_MESSAGES: List<String> = listOf(
  "完毕",
  "没找到",
  "没有符合条件的结果",
  "今天已经签到",
  "找不到用户",
)

val AUTH_LEVEL_SERVER_MESSAGES: List<String> = listOf("未登录")

fun isFakeError(message: String): Boolean = FAKE_ERROR_MESSAGES.any { message.contains(it) }

fun isAuthLevelServerError(message: String): Boolean =
  AUTH_LEVEL_SERVER_MESSAGES.any { message.contains(it) }

fun extractServerError(root: JsonElement?): NgaServerError? {
  if (root !is JsonObject) return null
  val error = root["error"] ?: return null
  if (error is JsonNull) return null

  if (error is JsonPrimitive && error.isString) {
    if (error.content.isEmpty()) return null
    val message = stripServerHtml(error.content)
    return NgaServerError(
      UNKNOWN_SERVER_CODE,
      message.ifEmpty { unknownMessage(UNKNOWN_SERVER_CODE) },
    )
  }

  if (error is JsonArray) {
    val messages = error
      .filter { it is JsonPrimitive && it.isString }
      .map { stripServerHtml((it as JsonPrimitive).content) }
      .filter { it.isNotEmpty() }
    return if (messages.isEmpty()) null else NgaServerError(UNKNOWN_SERVER_CODE, messages.joinToString("；"))
  }

  if (error !is JsonObject) return null

  val code = serverCodeOf(error["code"])

  val messages = mutableListOf<String>()
  for ((key, value) in jsOwnEntries(error)) {
    if (key == "code") continue
    if (value !is JsonPrimitive || !value.isString) continue
    val message = stripServerHtml(value.content)
    if (message.isNotEmpty()) messages += message
  }
  if (messages.isEmpty()) return NgaServerError(code, unknownMessage(code))
  return NgaServerError(code, messages.joinToString("；"))
}

private fun serverCodeOf(raw: JsonElement?): JsonPrimitive {
  if (raw !is JsonPrimitive || raw is JsonNull) return UNKNOWN_SERVER_CODE
  if (raw.isString) return raw
  return if (raw.content.toDoubleOrNull() != null) raw else UNKNOWN_SERVER_CODE
}

private fun unknownMessage(code: JsonPrimitive): String = "未知错误（code=${code.content}）"
