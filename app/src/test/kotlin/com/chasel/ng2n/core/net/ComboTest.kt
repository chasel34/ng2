package com.chasel.ng2n.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐条移植自 `src/core/net/combo.test.ts`(五组共 15 条,全部移植)。
 */
class ComboTest {

  private fun ids(combos: List<FetchCombo>) = combos.map { "${it.format.wire}@${it.host}" }

  private val formats = listOf(ResponseFormat.JSON, ResponseFormat.JSON_LITE)
  private val hosts = listOf("https://a", "https://b")

  // ── interfaceKeyOf · 成功组合按「接口」缓存 ────────────────────────────────

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

  // ── enumerateCombos · 格式 × 域名 ─────────────────────────────────────────

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
    // 上限再离谱也要发一次,否则这一档等于不存在
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

  // ── 组合缓存 ──────────────────────────────────────────────────────────────

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
    // 「全组合都失败才清缓存」这一条出口不够用:组合半通不通(能解析但没有业务数据)时
    // 缓存永远清不掉,唯一复位手段变成杀进程(2026-08-13「版块全空」排查)
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

    // 过期的不列出来
    clock = 1200
    assertEquals(listOf("read.php"), cache.entries().map { it.first })
  }

  // ── formatParamsOf · 诊断日志里要看得出实际发的是什么 ──────────────────────

  @Test
  fun `给出格式档位对应的 query 参数`() {
    assertEquals("__output=8", formatParamsOf(ResponseFormat.JSON))
    assertEquals("lite=js", formatParamsOf(ResponseFormat.JSON_LITE))
    assertEquals("(无格式参数)", formatParamsOf(ResponseFormat.HTML))
  }

  // ── 默认轮换表 · 冗余来自「不共用同一段服务端代码」 ────────────────────────

  @Test
  fun `jsonVerbose 在表里,而且排在 jsonLite 前面`() {
    // fid=414 的教训:`json`(__output=8) 与 `jsonLite`(lite=js) 是**同一份字节**,
    // 只差一层 `window.script_muti_get_var_store=` 包装。服务端把坏字节写进那份响应时,
    // 这两档一起完蛋,换几个域名都一样。`jsonVerbose`(__output=11) 是另一个序列化器,
    // 必须在耗掉一整轮域名之前就试到它。
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

    // 第一个域名把三个格式都试到(坏字节靠换格式救)
    assertEquals(
      listOf("json@https://a", "jsonVerbose@https://a", "jsonLite@https://a"),
      ids(combos).take(3),
    )
    // 同时保住三域名覆盖(被封靠换域名救),两者都不能丢
    assertEquals(3, combos.map { it.host }.toSet().size)
  }
}
