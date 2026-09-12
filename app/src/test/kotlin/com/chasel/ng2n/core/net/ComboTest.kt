package com.chasel.ng2n.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComboTest {

  private fun ids(combos: List<FetchCombo>) = combos.map { "${it.format.wire}@${it.host}" }

  private val formats = listOf(ResponseFormat.JSON, ResponseFormat.JSON_LITE)
  private val hosts = listOf("https://a", "https://b")

  @Test
  fun `光有 path 的接口就用 path`() {
    assertEquals(
      "thread.php",
      interfaceKeyOf(readRequest("thread.php", queryOf("fid" to 650))),
    )
  }

  @Test
  fun `nuke_php 底下按 lib 与 act 细分——被封的粒度是接口不是脚本文件`() {
    val noti = interfaceKeyOf(
      readRequest("nuke.php", queryOf("__lib" to "noti", "__act" to "get_all")),
    )
    val ucp = interfaceKeyOf(readRequest("nuke.php", queryOf("__lib" to "ucp", "__act" to "get")))

    assertEquals("nuke.php?__lib=noti&__act=get_all", noti)
    assertNotEquals(noti, ucp)
  }

  @Test
  fun `同一接口的业务参数不进 key·tid 换了不该重新试探`() {
    assertEquals(
      interfaceKeyOf(readRequest("read.php", queryOf("tid" to 1))),
      interfaceKeyOf(readRequest("read.php", queryOf("tid" to 2))),
    )
  }

  @Test
  fun `域名外层格式内层·换格式比换域名便宜,先在同一台上换`() {
    assertEquals(
      listOf("json@https://a", "jsonLite@https://a", "json@https://b", "jsonLite@https://b"),
      ids(enumerateCombos(formats, hosts, maxAttempts = 10)),
    )
  }

  @Test
  fun `缓存里的成功组合排第一,且不会再出现第二次`() {
    val combos = enumerateCombos(
      formats,
      hosts,
      maxAttempts = 10,
      preferred = FetchCombo(ResponseFormat.JSON_LITE, "https://b"),
    )
    assertEquals("jsonLite@https://b", ids(combos).first())
    assertEquals(4, combos.size)
  }

  @Test
  fun `调用方指定的组合排在轮换前面,但不独占——它照样可能被封`() {
    val combos = enumerateCombos(
      formats,
      hosts,
      maxAttempts = 10,
      requested = FetchCombo(ResponseFormat.JSON_VERBOSE, "https://c"),
    )
    assertEquals("jsonVerbose@https://c", ids(combos).first())
    assertEquals(5, combos.size)
  }

  @Test
  fun `上限截断,免得让用户等十几个来回`() {
    assertEquals(3, enumerateCombos(formats, hosts, maxAttempts = 3).size)
    assertEquals(1, enumerateCombos(formats, hosts, maxAttempts = 0).size)
  }

  @Test
  fun `点名要 XML 或 HTML 时不轮换·那条路线上还没有解析器`() {
    val combos = enumerateCombos(
      formats,
      hosts,
      maxAttempts = 10,
      requested = FetchCombo(ResponseFormat.XML, "https://a"),
    )
    assertEquals(listOf("xml@https://a"), ids(combos))
  }

  @Test
  fun `不可解析的格式不进轮换`() {
    assertTrue(isRotatableFormat(ResponseFormat.JSON))
    assertTrue(!isRotatableFormat(ResponseFormat.XML))
    assertTrue(!isRotatableFormat(ResponseFormat.HTML))

    assertEquals(
      listOf("json@https://a"),
      ids(
        enumerateCombos(
          listOf(ResponseFormat.JSON, ResponseFormat.XML),
          listOf("https://a"),
          maxAttempts = 10,
        ),
      ),
    )
  }

  @Test
  fun `记住、读回、清掉`() {
    val cache = InMemoryComboCache()
    val combo = FetchCombo(ResponseFormat.JSON_LITE, "https://a")

    assertNull(cache.get("read.php"))
    cache.remember("read.php", combo)
    assertEquals(combo, cache.get("read.php"))
    assertNull(cache.get("thread.php"))
    cache.forget("read.php")
    assertNull(cache.get("read.php"))
  }

  @Test
  fun `条目有保质期·过期后当没记过,重新从默认组合试探`() {
    var clock = 0L
    val cache = InMemoryComboCache(ttlMs = 1000) { clock }
    cache.remember("thread.php", FetchCombo(ResponseFormat.JSON, "https://a"))

    clock = 999
    assertEquals(FetchCombo(ResponseFormat.JSON, "https://a"), cache.get("thread.php"))
    clock = 1000
    assertNull(cache.get("thread.php"))
  }

  @Test
  fun `entries 给出当前记着的全部组合(实验室页的「本次运行的组合」)`() {
    var clock = 0L
    val cache = InMemoryComboCache(ttlMs = 1000) { clock }
    cache.remember("thread.php", FetchCombo(ResponseFormat.JSON, "https://a"))
    clock = 500
    cache.remember("read.php", FetchCombo(ResponseFormat.JSON_LITE, "https://b"))

    assertEquals(
      listOf(
        "thread.php" to ComboRecord(FetchCombo(ResponseFormat.JSON, "https://a"), 0),
        "read.php" to ComboRecord(FetchCombo(ResponseFormat.JSON_LITE, "https://b"), 500),
      ),
      cache.entries(),
    )

    clock = 1200
    assertEquals(listOf("read.php"), cache.entries().map { it.first })
  }

  @Test
  fun `给出格式档位对应的 query 参数`() {
    assertEquals("__output=8", formatParamsOf(ResponseFormat.JSON))
    assertEquals("lite=js", formatParamsOf(ResponseFormat.JSON_LITE))
    assertEquals("(无格式参数)", formatParamsOf(ResponseFormat.HTML))
  }

  @Test
  fun `jsonVerbose 在表里,而且排在 jsonLite 前面`() {
    assertTrue(DEFAULT_ROTATION_FORMATS.contains(ResponseFormat.JSON_VERBOSE))
    assertTrue(
      DEFAULT_ROTATION_FORMATS.indexOf(ResponseFormat.JSON_VERBOSE) <
        DEFAULT_ROTATION_FORMATS.indexOf(ResponseFormat.JSON_LITE),
    )
  }

  @Test
  fun `默认上限够第一个域名试满所有格式,还剩得下第二第三个域名`() {
    val combos = enumerateCombos(
      DEFAULT_ROTATION_FORMATS,
      listOf("https://a", "https://b", "https://c"),
      maxAttempts = DEFAULT_MAX_ATTEMPTS,
    )

    assertEquals(
      listOf("json@https://a", "jsonVerbose@https://a", "jsonLite@https://a"),
      ids(combos).take(3),
    )
    assertEquals(3, combos.map { it.host }.toSet().size)
  }
}
