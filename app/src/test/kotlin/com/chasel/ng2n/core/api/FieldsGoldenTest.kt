package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * `api/fields` domain 全量对拍(37 条,票 04)。
 *
 * `orderedEntries` / `orderedValues` / `nonZero` 的 input 是 `{ value }`;
 * `str` / `text` / `int` 是 `{ record, key }`。
 * `orderedEntries` 的 expected 是 `[[key, value], …]`——**真数组也要当列表遍历**
 * (`__output=11` 的 `__T` 是货真价实的 JSON 数组,不认它整页主题会静默变 0 条)。
 */
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

/** `{ "value": … }`;`null` 在这里是真的 `null`(TS 侧就传的 `null`)。 */
private fun GoldenCase.value() = field("value")

private fun GoldenCase.record(): JsonObject = field("record").jsonObject

/** `nonZero` 的入参在 TS 侧是 `number | undefined`,JSON 里 `null` = 没有。 */
private fun GoldenCase.longValue(): Long? {
  val raw = field("value")
  if (raw is JsonNull) return null
  return (raw as JsonPrimitive).content.toLong()
}
