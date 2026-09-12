package com.chasel.ng2n.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 手工移植 `src/core/api/fields.test.ts`,外加一条金样本**表达不了**的用例。
 *
 * 金样本的规范化会把对象键排成字典序,于是「非数字键按插入序而不是字典序」这半条语义
 * 在文件里存不下来(票 04 发现,与 `query` domain 那次同源)。那一半只能在这里锁:
 * 这边可以显式构造一个键序为 `b, 1, a, 0` 的 [JsonObject]。
 */
class FieldsTest {

  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

  private fun keysOf(value: JsonElement) = orderedEntries(value).map { it.first }

  private fun valuesOf(value: JsonElement) = orderedValues(value).map {
    (it as JsonPrimitive).content
  }

  /** `__output=8` 的习惯(API 文档 §0.6):字符串数字键当数组,按**数值**升序而不是字典序。 */
  @Test
  fun `字符串数字键的对象按数字升序`() {
    assertEquals(listOf("a", "c", "j"), valuesOf(json("{\"10\":\"j\",\"2\":\"c\",\"0\":\"a\"}")))
    assertEquals(listOf("0", "2", "10"), keysOf(json("{\"10\":\"j\",\"2\":\"c\",\"0\":\"a\"}")))
  }

  /**
   * 票面要求的专门用例:**真数组也当列表遍历**。
   *
   * `__output=11` 的 `__T` 是货真价实的 JSON 数组。TS 的 `isRecord` 按约定把数组排除在外,
   * 2026-08-14 因此让整页主题静默变成 0 条(表现是「fid=414 版块打不开」,
   * 比报错难查得多;ADR-0002 第 10 条)。
   */
  @Test
  fun `真数组也当列表遍历`() {
    assertEquals(listOf("a", "c", "j"), valuesOf(json("[\"a\",\"c\",\"j\"]")))
    assertEquals(listOf("0", "1"), keysOf(json("[\"a\",\"c\"]")))
    assertEquals(
      listOf("0" to "a", "1" to "c"),
      orderedEntries(json("[\"a\",\"c\"]")).map { it.first to (it.second as JsonPrimitive).content },
    )
  }

  /** 空数组与空对象一样给空列表,不是「解析失败」——空版块是正常结果。 */
  @Test
  fun `空数组和空对象都给空列表`() {
    assertEquals(emptyList(), orderedValues(json("[]")))
    assertEquals(emptyList(), orderedValues(json("{}")))
  }

  @Test
  fun `既不是对象也不是数组时给空列表`() {
    assertEquals(emptyList(), orderedValues(JsonNull))
    assertEquals(emptyList(), orderedValues(json("\"abc\"")))
    assertEquals(emptyList(), orderedValues(json("42")))
    assertEquals(emptyList(), orderedValues(null))
  }

  /**
   * 非数字键排在数字键后面,**保持原有顺序**(不是字典序)。
   *
   * 这条金样本存不下来:导出规范会把 `input` 的对象键排成字典序,`b` 就永远排在 `a` 后面了。
   * 这里显式按 `b, 1, a, 0` 的顺序建对象,期望是 `0, 1, b, a`。
   */
  @Test
  fun `非数字键排在数字键后面并保持原有顺序`() {
    val value = JsonObject(
      linkedMapOf(
        "b" to JsonPrimitive(2),
        "1" to JsonPrimitive("x"),
        "a" to JsonPrimitive(1),
        "0" to JsonPrimitive("y"),
      ),
    )

    assertEquals(listOf("0", "1", "b", "a"), keysOf(value))
    assertEquals(listOf("y", "x", "2", "1"), valuesOf(value))
  }

  // --- str / text / int / nonZero -------------------------------------------

  private val record = json(
    """
    {
      "name": "  原神  ",
      "empty": "   ",
      "subject": "&lt;第六感&gt;那个小孩",
      "emojiSubject": "&amp;#55357;&amp;#56836;",
      "count": 12,
      "countText": " 34 ",
      "float": 3.9,
      "bad": "abc",
      "zero": 0,
      "nested": {}
    }
    """.trimIndent(),
  ).jsonObject

  @Test
  fun `str 取非空字符串并 trim`() {
    assertEquals("原神", str(record, "name"))
    assertNull(str(record, "empty"), "全是空白算「没有」")
    assertNull(str(record, "count"), "数字不是字符串")
    assertNull(str(record, "nested"))
    assertNull(str(record, "missing"))
    assertEquals("&lt;第六感&gt;那个小孩", str(record, "subject"), "str 不做实体解码")
  }

  /** `subject` 也会被服务端 HTML 转义,走与正文同一套两轮解码(emoji 的代理对实体一并还原)。 */
  @Test
  fun `text 在 str 之上做两轮实体解码`() {
    assertEquals("<第六感>那个小孩", text(record, "subject"))
    assertEquals("😄", text(record, "emojiSubject"))
    assertEquals("原神", text(record, "name"))
    assertNull(text(record, "missing"))
  }

  @Test
  fun `int 收数字也收写成字符串的数字`() {
    assertEquals(12L, int(record, "count"))
    assertEquals(34L, int(record, "countText"))
    assertEquals(3L, int(record, "float"), "向零截断")
    assertNull(int(record, "bad"))
    assertEquals(0L, int(record, "zero"))
    assertNull(int(record, "nested"))
    assertNull(int(record, "missing"))
  }

  /**
   * 逆向怪癖,勿修:`Number('')` 在 JS 里是 `0`,所以全空白的字段值取整数会得到 `0`
   * 而不是「没有」。RN 版就是这个行为,下游的 `nonZero` 正好把它抹平。
   */
  @Test
  fun `int 对全空白字段给 0 而不是 null`() {
    assertEquals(0L, int(record, "empty"))
  }

  /** 布尔与 `null` 在 TS 侧 `typeof` 既不是 number 也不是 string ⇒ 没有这个值。 */
  @Test
  fun `int 不认布尔与 null`() {
    val weird = json("{\"flag\":true,\"nothing\":null}").jsonObject

    assertNull(int(weird, "flag"))
    assertNull(int(weird, "nothing"))
  }

  /** NGA 分不清「字段缺省」与「填 0」——普通版块就常带一个 `stid:0`(= 不是合集)。 */
  @Test
  fun `nonZero 把 0 当成没有`() {
    assertEquals(7L, nonZero(7L))
    assertEquals(-7L, nonZero(-7L))
    assertNull(nonZero(0L))
    assertNull(nonZero(null))
  }

  /** `credit` / `bit` 这些权限位图会越过 32 位,收成 Int 会静默截断。 */
  @Test
  fun `int 收得下超过 32 位的值`() {
    val big = json("{\"credit\":135537153000}").jsonObject

    assertEquals(135537153000L, int(big, "credit"))
  }
}
