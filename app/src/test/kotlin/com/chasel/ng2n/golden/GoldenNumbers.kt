package com.chasel.ng2n.golden

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 数字型入参的取值助手(票 10 加)。
 *
 * `GoldenCase` 自带的是字符串/布尔那几个;逆向算法这一批的 `input` 里大量是
 * tid / pid / 时间戳 / 铜币数,统一从这里取,免得每个 domain 各写一遍 `jsonPrimitive.long`。
 */

fun GoldenCase.longField(key: String): Long = field(key).jsonPrimitive.long

fun GoldenCase.longFieldOrNull(key: String): Long? {
  val value = inputObject()[key] ?: return null
  if (value is JsonNull) return null
  return value.jsonPrimitive.long
}

fun GoldenCase.intField(key: String): Int = longField(key).toInt()

fun GoldenCase.intFieldOrNull(key: String): Int? = longFieldOrNull(key)?.toInt()

fun GoldenCase.doubleField(key: String): Double = field(key).jsonPrimitive.double

/**
 * `unknown` 型入参(TS 侧签名就是 `unknown`:同一个字段服务端发过字符串、数字,也缺席过)。
 * 缺键与 `null` 都给 `null`;字符串给 `String`;数字给 `Double`。
 */
fun GoldenCase.unknownField(key: String): Any? {
  val value = inputObject()[key] ?: return null
  if (value is JsonNull) return null
  val primitive = value as? JsonPrimitive ?: return value
  return if (primitive.isString) primitive.content else primitive.double
}
