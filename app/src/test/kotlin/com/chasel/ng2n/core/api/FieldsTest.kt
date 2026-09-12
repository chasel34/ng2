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

class FieldsTest {

  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

  private fun keysOf(value: JsonElement) = orderedEntries(value).map { it.first }

  private fun valuesOf(value: JsonElement) = orderedValues(value).map {
    (it as JsonPrimitive).content
  }

  @Test
  fun `字符串数字键的对象按数字升序`() {
    assertEquals(listOf("a", "c", "j"), valuesOf(json("{\"10\":\"j\",\"2\":\"c\",\"0\":\"a\"}")))
    assertEquals(listOf("0", "2", "10"), keysOf(json("{\"10\":\"j\",\"2\":\"c\",\"0\":\"a\"}")))
  }

  @Test
  fun `真数组也当列表遍历`() {
    assertEquals(listOf("a", "c", "j"), valuesOf(json("[\"a\",\"c\",\"j\"]")))
    assertEquals(listOf("0", "1"), keysOf(json("[\"a\",\"c\"]")))
    assertEquals(
      listOf("0" to "a", "1" to "c"),
      orderedEntries(json("[\"a\",\"c\"]")).map { it.first to (it.second as JsonPrimitive).content },
    )
  }

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

  @Test
  fun `int 对全空白字段给 0 而不是 null`() {
    assertEquals(0L, int(record, "empty"))
  }

  @Test
  fun `int 不认布尔与 null`() {
    val weird = json("{\"flag\":true,\"nothing\":null}").jsonObject

    assertNull(int(weird, "flag"))
    assertNull(int(weird, "nothing"))
  }

  @Test
  fun `nonZero 把 0 当成没有`() {
    assertEquals(7L, nonZero(7L))
    assertEquals(-7L, nonZero(-7L))
    assertNull(nonZero(0L))
    assertNull(nonZero(null))
  }

  @Test
  fun `int 收得下超过 32 位的值`() {
    val big = json("{\"credit\":135537153000}").jsonObject

    assertEquals(135537153000L, int(big, "credit"))
  }
}
