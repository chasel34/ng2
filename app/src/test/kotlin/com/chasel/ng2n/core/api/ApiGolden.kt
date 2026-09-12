package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.EnvelopeShape
import com.chasel.ng2n.core.net.NgaEnvelope
import com.chasel.ng2n.core.net.NgaServerError
import com.chasel.ng2n.core.net.UNKNOWN_SERVER_CODE
import com.chasel.ng2n.core.net.parseNgaJson
import com.chasel.ng2n.golden.GoldenCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

val ApiGoldenJson: Json = Json {
  explicitNulls = false
  encodeDefaults = true
  classDiscriminator = "kind"
}

inline fun <reified T> golden(value: T): JsonElement = ApiGoldenJson.encodeToJsonElement(value)

fun GoldenCase.valueField(): JsonElement = inputObject()["value"] ?: JsonNull

fun GoldenCase.arg(key: String): JsonElement? = (inputObject()["args"] as? JsonObject)?.get(key)

fun GoldenCase.envelopePart(): JsonElement? {
  val text = stringField("text")
  val shape = when (val declared = stringFieldOrNull("envelope")) {
    null, "wrapped" -> EnvelopeShape.WRAPPED
    "bare" -> EnvelopeShape.BARE
    else -> error("$resourcePath: 认不出的 envelope `$declared`")
  }
  val envelope = parseNgaJson(text, "golden", shape)
  return when (val part = stringFieldOrNull("part")) {
    null, "data" -> envelope.data
    "root" -> envelope.root
    else -> error("$resourcePath: 认不出的 part `$part`")
  }
}

fun GoldenCase.parserInput(): JsonElement? =
  if (inputObject().containsKey("text")) envelopePart() else valueField()

fun GoldenCase.envelopeInput(): NgaEnvelope {
  val fake = inputObject()["fakeError"] as? JsonObject
  return NgaEnvelope(
    root = JsonObject(emptyMap()),
    data = inputObject()["data"],
    fakeError = fake?.let {
      NgaServerError(
        code = it["code"] as? JsonPrimitive ?: UNKNOWN_SERVER_CODE,
        message = (it["message"] as? JsonPrimitive)?.content ?: "",
      )
    },
  )
}
