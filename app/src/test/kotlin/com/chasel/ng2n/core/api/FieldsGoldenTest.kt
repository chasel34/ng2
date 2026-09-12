package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class FieldsGoldenTest {

  @Test
  fun `api-fields 金样本全量对拍`() = runGoldenDomain("api/fields") {
    fn("orderedEntries") { case ->
      JsonArray(
        orderedEntries(case.value()).map { (key, item) ->
          JsonArray(listOf(JsonPrimitive(key), item))
        },
      )
    }
    fn("orderedValues") { case -> JsonArray(orderedValues(case.value())) }
    fn("str") { case -> str(case.record(), case.stringField("key")) }
    fn("text") { case -> text(case.record(), case.stringField("key")) }
    fn("int") { case -> int(case.record(), case.stringField("key")) }
    fn("nonZero") { case -> nonZero(case.longValue()) }
  }
}

private fun GoldenCase.value() = field("value")

private fun GoldenCase.record(): JsonObject = field("record").jsonObject

private fun GoldenCase.longValue(): Long? {
  val raw = field("value")
  if (raw is JsonNull) return null
  return (raw as JsonPrimitive).content.toLong()
}
