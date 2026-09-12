package com.chasel.ng2n.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `NgaJson` 的三个开关与 [NgaListSerializer] 样板。
 *
 * 二象性字段真正的归一是票 07 逐个端点的事,这里只证样板能用:
 * **同一段 Kotlin 代码要能同时吃下 `__output=8` 的「数字键对象」与 `__output=11` 的真数组**
 * (ADR-0002 第 10 条:不认真数组的后果是整页主题静默变 0 条,比抛错难查)。
 */
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

  /** NGA 随时加字段,加一个就崩掉是不能接受的。 */
  @Test
  fun `未知字段被忽略`() {
    val rows = decode("[{\"tid\":1,\"subject\":\"x\",\"下次一定有的新字段\":42}]")

    assertEquals(listOf(Row(1, "x")), rows)
  }

  /** 非空字段收到 `null` 时退回默认值,而不是抛。 */
  @Test
  fun `null 被强制成默认值`() {
    val rows = decode("[{\"tid\":1,\"subject\":null}]")

    assertEquals(listOf(Row(1, "")), rows)
  }

  /** 值不带引号的写法不该让整条响应作废。 */
  @Test
  fun `宽容档收得下不带引号的值`() {
    assertEquals("原神", NgaJson.decodeFromString(String.serializer(), "原神"))
  }
}
