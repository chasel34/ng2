package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test

/**
 * `query` domain 全量对拍(票 03)。
 *
 * `input` 是 `{ "params": [[key, value], …] }` —— **有序的键值对列表,不是对象**:
 * 导出器的规范化会把对象键排成字典序,而 `buildQueryString` 拼出来的串是**插入序**
 * (`post-form-same-rules` 那条就靠这个区分)。这一处是 05b 开工时发现并回改 05a 导出器的,
 * 详见票 03 的 Comments。
 */
class QueryGoldenTest {

  @Test
  fun `query 金样本全量对拍`() = runGoldenDomain("query") {
    fn("buildQueryString") { case -> buildQueryString(case.params()!!) }
    fn("hasGbkParam") { case -> hasGbkParam(case.params()) }
  }
}

/** `input` → 参数表;`input` 整个是 `null` 表示 TS 侧传的 `undefined`。 */
private fun GoldenCase.params(): QueryParams? {
  if (input is JsonNull) return null
  val pairs = inputObject().getValue("params").jsonArray
  val out = LinkedHashMap<String, QueryValue?>(pairs.size * 2)
  for (pair in pairs) {
    val entry = pair as JsonArray
    val key = (entry[0] as JsonPrimitive).content
    out[key] = toQueryValue(entry[1])
  }
  return out
}

private fun toQueryValue(element: JsonElement): QueryValue? = when (element) {
  is JsonNull -> null
  // gbk() 标记在 JSON 里就是它的运行时形态 { charset: "gbk", value: … }
  is JsonObject -> {
    check((element["charset"] as? JsonPrimitive)?.content == "gbk") { "认不出的参数值:$element" }
    QueryValue.Gbk((element.getValue("value") as JsonPrimitive).content)
  }
  is JsonPrimitive -> when {
    element.isString -> QueryValue.Text(element.content)
    element.booleanOrNull != null -> QueryValue.Flag(element.booleanOrNull!!)
    element.longOrNull != null -> QueryValue.Num(element.longOrNull!!)
    else -> error("认不出的参数值:$element")
  }
  else -> error("认不出的参数值:$element")
}
