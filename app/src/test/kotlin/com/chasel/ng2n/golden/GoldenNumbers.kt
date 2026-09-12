package com.chasel.ng2n.golden

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

fun GoldenCase.longField(key: String): Long = field(key).jsonPrimitive.long

fun GoldenCase.longFieldOrNull(key: String): Long? {
  val value = inputObject()[key] ?: return null
  if (value is JsonNull) return null
  return value.jsonPrimitive.long
}

fun GoldenCase.intField(key: String): Int = longField(key).toInt()

fun GoldenCase.intFieldOrNull(key: String): Int? = longFieldOrNull(key)?.toInt()

fun GoldenCase.doubleField(key: String): Double = field(key).jsonPrimitive.double

fun GoldenCase.unknownField(key: String): Any? {
  val value = inputObject()[key] ?: return null
  if (value is JsonNull) return null
  val primitive = value as? JsonPrimitive ?: return value
  return if (primitive.isString) primitive.content else primitive.double
}
