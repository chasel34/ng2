package com.chasel.ng2n.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class NgaJsonTest {

  @Serializable
  private data class Row(val tid: Long = 0, val subject: String = "")

  private object RowListSerializer : NgaListSerializer<Row>(Row.serializer())

  private fun decode(text: String): List<Row> =
    NgaJson.decodeFromString(RowListSerializer, text)

  @Test
  fun `数字键对象解成列表,按数字升序`() {
    val rows = decode(
      "{\"10\":{\"tid\":10,\"subject\":\"j\"},\"2\":{\"tid\":2,\"subject\":\"c\"}," +
        "\"0\":{\"tid\":0,\"subject\":\"a\"}}",
    )

    assertEquals(listOf(0L, 2L, 10L), rows.map { it.tid })
  }

  @Test
  fun `真数组解成同一个列表`() {
    val rows = decode("[{\"tid\":0,\"subject\":\"a\"},{\"tid\":2,\"subject\":\"c\"}]")

    assertEquals(listOf(0L, 2L), rows.map { it.tid })
  }

  @Test
  fun `不是列表的形态给空列表而不是抛错`() {
    assertEquals(emptyList(), decode("null"))
    assertEquals(emptyList(), decode("\"\""))
  }

  @Test
  fun `未知字段被忽略`() {
    val rows = decode("[{\"tid\":1,\"subject\":\"x\",\"下次一定有的新字段\":42}]")

    assertEquals(listOf(Row(1, "x")), rows)
  }

  @Test
  fun `null 被强制成默认值`() {
    val rows = decode("[{\"tid\":1,\"subject\":null}]")

    assertEquals(listOf(Row(1, "")), rows)
  }

  @Test
  fun `宽容档收得下不带引号的值`() {
    assertEquals("原神", NgaJson.decodeFromString(String.serializer(), "原神"))
  }
}
