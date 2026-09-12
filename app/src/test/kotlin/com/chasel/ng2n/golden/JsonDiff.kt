package com.chasel.ng2n.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

data class JsonDiff(val path: String, val expected: String, val actual: String, val detail: String? = null)

object JsonDeepCompare {

  private const val ABSENT = "<缺键>"

  fun diff(expected: JsonElement, actual: JsonElement): List<JsonDiff> {
    val out = mutableListOf<JsonDiff>()
    walk("$", expected, actual, out)
    return out
  }

  private fun walk(path: String, expected: JsonElement, actual: JsonElement, out: MutableList<JsonDiff>) {
    when {
      expected is JsonObject && actual is JsonObject -> walkObject(path, expected, actual, out)
      expected is JsonArray && actual is JsonArray -> walkArray(path, expected, actual, out)
      expected is JsonNull || actual is JsonNull -> {
        if (expected !is JsonNull || actual !is JsonNull) out += mismatch(path, expected, actual)
      }
      expected is JsonPrimitive && actual is JsonPrimitive -> walkPrimitive(path, expected, actual, out)
      else -> out += mismatch(path, expected, actual)
    }
  }

  private fun walkObject(path: String, expected: JsonObject, actual: JsonObject, out: MutableList<JsonDiff>) {
    for (key in expected.keys) {
      val child = "$path${step(key)}"
      val expectedValue = expected.getValue(key)
      val actualValue = actual[key]
      if (actualValue == null) {
        out += JsonDiff(child, expectedValue.preview(), ABSENT)
      } else {
        walk(child, expectedValue, actualValue, out)
      }
    }
    for (key in actual.keys) {
      if (key !in expected) out += JsonDiff("$path${step(key)}", ABSENT, actual.getValue(key).preview())
    }
  }

  private fun walkArray(path: String, expected: JsonArray, actual: JsonArray, out: MutableList<JsonDiff>) {
    if (expected.size != actual.size) {
      out += JsonDiff("$path.length", expected.size.toString(), actual.size.toString())
    }
    for (i in 0 until minOf(expected.size, actual.size)) {
      walk("$path[$i]", expected[i], actual[i], out)
    }
    for (i in actual.size until expected.size) {
      out += JsonDiff("$path[$i]", expected[i].preview(), ABSENT)
    }
    for (i in expected.size until actual.size) {
      out += JsonDiff("$path[$i]", ABSENT, actual[i].preview())
    }
  }

  private fun walkPrimitive(path: String, expected: JsonPrimitive, actual: JsonPrimitive, out: MutableList<JsonDiff>) {
    if (expected.isString != actual.isString) {
      out += mismatch(path, expected, actual)
      return
    }
    if (expected.isString) {
      if (expected.content != actual.content) {
        out += JsonDiff(
          path,
          quote(clip(expected.content)),
          quote(clip(actual.content)),
          describeStringDiff(expected.content, actual.content),
        )
      }
      return
    }
    val expectedNumber = expected.content.toBigDecimalOrNull()
    val actualNumber = actual.content.toBigDecimalOrNull()
    val equal = if (expectedNumber != null && actualNumber != null) {
      expectedNumber.compareTo(actualNumber) == 0
    } else {
      expected.content == actual.content
    }
    if (!equal) out += mismatch(path, expected, actual)
  }

  private fun mismatch(path: String, expected: JsonElement, actual: JsonElement) =
    JsonDiff(path, expected.preview(), actual.preview())

  private fun step(key: String): String =
    if (key.isNotEmpty() && key.all { it == '_' || it.isLetterOrDigit() } && !key.first().isDigit()) {
      ".$key"
    } else {
      "[${quote(key)}]"
    }

  private fun clip(text: String, limit: Int = 120): String =
    if (text.length <= limit) text else text.take(limit) + "…(共 ${text.length} 码元)"

  private fun describeStringDiff(expected: String, actual: String): String {
    var i = 0
    while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
    if (i == expected.length && i == actual.length) return ""
    val from = maxOf(0, i - 24)
    val expectedTo = minOf(expected.length, i + 24)
    val actualTo = minOf(actual.length, i + 24)
    return buildString {
      append("首处不同在码元 #$i(期望长 ${expected.length},实际长 ${actual.length})\n")
      append("        期望窗口 ${quote(expected.substring(from, expectedTo))}\n")
      append("        实际窗口 ${quote(actual.substring(from, actualTo))}")
      if (i < expected.length && i < actual.length) {
        append("\n        期望 U+%04X vs 实际 U+%04X".format(expected[i].code, actual[i].code))
      }
    }
  }
}
