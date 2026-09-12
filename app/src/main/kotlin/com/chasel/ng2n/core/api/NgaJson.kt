package com.chasel.ng2n.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

val NgaJson: Json = Json {
  isLenient = true
  ignoreUnknownKeys = true
  coerceInputValues = true
  explicitNulls = false
}

open class NgaListSerializer<T>(elementSerializer: KSerializer<T>) :
  JsonTransformingSerializer<List<T>>(ListSerializer(elementSerializer)) {

  override fun transformDeserialize(element: JsonElement): JsonElement =
    JsonArray(orderedValues(element))
}
