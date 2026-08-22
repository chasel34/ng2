package com.chasel.ng2n.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * 端点层解 `@Serializable` 数据类用的 [Json]。
 *
 * 三个开关都是被 NGA 的响应逼出来的:
 * - `isLenient` —— 值不带引号的写法(`{"replies":,}` 之类)不该整条响应作废;
 * - `ignoreUnknownKeys` —— NGA 随时加字段,加一个就崩掉是不能接受的;
 * - `coerceInputValues` —— 非空字段收到 `null` 时退回默认值,而不是抛。
 *
 * ⚠️ **信封层不用这一档**:`parseNgaJson` 走的是 `core/net/Envelope.kt` 里的严格档,
 * 因为「洗不成 JSON ⇒ 大概率被封 ⇒ 换下一个组合」这条信号必须留住。理由写在那边。
 */
val NgaJson: Json = Json {
  isLenient = true
  ignoreUnknownKeys = true
  coerceInputValues = true
  explicitNulls = false
}

/**
 * **二象性字段归一的工具样板**(票 04 只提供样板,逐个端点怎么用是票 07 的事)。
 *
 * NGA 的「列表」有两副面孔:`__output=8` 用字符串数字键的对象冒充数组,
 * `__output=11` 下发货真价实的 JSON 数组(ADR-0002 第 10 条)。两种都要能解成同一个
 * `List<T>`,否则整页主题会静默变成 0 条。
 *
 * 用法:
 * ```kotlin
 * @Serializable
 * data class TopicPage(
 *   @SerialName("__T")
 *   @Serializable(with = NgaListSerializer::class)   // 需要具体元素类型时写成自己的子类
 *   val topics: List<TopicRow> = emptyList(),
 * )
 * ```
 * 或者不写注解,直接 `NgaListSerializer(TopicRow.serializer())` 传给 `decodeFromJsonElement`。
 *
 * 归一走的是 [orderedValues],所以「数字键升序、非数字键垫后」这条顺序语义与手工遍历
 * 那条路完全一致——两条路给出的楼层顺序不能有分歧。
 */
open class NgaListSerializer<T>(elementSerializer: KSerializer<T>) :
  JsonTransformingSerializer<List<T>>(ListSerializer(elementSerializer)) {

  override fun transformDeserialize(element: JsonElement): JsonElement =
    JsonArray(orderedValues(element))
}
