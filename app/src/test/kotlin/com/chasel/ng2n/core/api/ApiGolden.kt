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

/**
 * `api/…` 这一批 domain 的共用管线(goldens/README「`api/…` 的输入管线」)。
 *
 * 两种输入形态,**看有没有 `text` 字段**:
 * - 形态 A(走管线):`parseNgaJson(text, "golden", envelope)` → 取 `part` → 喂解析器;
 * - 形态 B(直接喂值):`input.value` 就是解析器的入参(合成向量,不经网络层)。
 */

/**
 * 对拍用的序列化档。
 *
 * - `explicitNulls = false` —— 导出器把值为 `undefined` 的键**整个删掉**(README 规范 2),
 *   Kotlin 侧的 `null` 字段必须跟着消失,否则每条都会多出一堆 `"favCode": null`;
 * - `encodeDefaults = true` —— `denied: Boolean = false` 这类**有默认值但必须出现**的字段
 *   不能因为「等于默认值」被省掉(kotlinx 默认省);
 * - `classDiscriminator = "kind"` —— `UserSearchQuery` 那个联合类型的判别键在金样本里叫 `kind`。
 */
val ApiGoldenJson: Json = Json {
  explicitNulls = false
  encodeDefaults = true
  classDiscriminator = "kind"
}

/** 领域模型 → `JsonElement`,好跟 `expected` 深比较。 */
inline fun <reified T> golden(value: T): JsonElement = ApiGoldenJson.encodeToJsonElement(value)

/** 形态 B 的入参(`input.value`);缺键与 `null` 都给 [JsonNull]。 */
fun GoldenCase.valueField(): JsonElement = inputObject()["value"] ?: JsonNull

/** `input.args.<key>`,没有 `args` 时给 null。 */
fun GoldenCase.arg(key: String): JsonElement? = (inputObject()["args"] as? JsonObject)?.get(key)

/** 形态 A:整条管线跑到 `part` 指定的那一段。 */
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

/** 两种形态自动分派:有 `text` 走管线,否则取 `value`。 */
fun GoldenCase.parserInput(): JsonElement? =
  if (inputObject().containsKey("text")) envelopePart() else valueField()

/**
 * `rejectNonTopicList` 的入参是**信封**而不是一段 data:
 * `{ data, fakeError? }`(`root` 与判定无关,给个空对象)。
 */
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
