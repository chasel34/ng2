package com.chasel.ng2n.golden

import com.chasel.ng2n.core.bbcode.escapeForSubmit
import com.chasel.ng2n.core.bbcode.unescapeNgaText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对拍框架自己的回归。
 *
 * 「金样本全绿」这句话值不值钱,取决于框架**红得起来**:比对放宽一格、少跑一条、
 * 把抛错当成通过,都会让后面十几张票的验收变成走过场。所以这里逐条钉死它的失败面。
 */
class GoldenFrameworkTest {

  private fun case(
    expected: String,
    input: String = "\"x\"",
    fn: String = "f",
    name: String = "self-test",
  ): GoldenCase = GoldenCase.from(
    "self",
    Json.parseToJsonElement("""{"expected":$expected,"fn":"$fn","input":$input,"name":"$name"}"""),
  )

  // --- 相等口径 ------------------------------------------------------------

  @Test
  fun `JSON 数字 1 与 1_0 视为相等`() {
    assertNull(checkGolden(case("1")) { 1.0 })
    assertNull(checkGolden(case("1.0")) { 1 })
    assertNull(checkGolden(case("[1,2]")) { listOf(1L, 2.0) })
  }

  @Test
  fun `数值不同要报出来`() {
    assertNotNull(checkGolden(case("1")) { 1.5 })
  }

  @Test
  fun `字符串逐码元比,不做规范化`() {
    // 组合字符 é(e + U+0301)与预组合 é(U+00E9)不是一回事
    assertNotNull(checkGolden(case("\"e\\u0301\"")) { "\u00e9" })
    assertNull(checkGolden(case("\"e\\u0301\"")) { "e\u0301" })
  }

  @Test
  fun `null 与缺键不等价`() {
    assertNotNull(checkGolden(case("""{"a":null}""")) { mapOf<String, Any?>() })
    assertNotNull(checkGolden(case("{}")) { mapOf("a" to null) })
    assertNull(checkGolden(case("""{"a":null}""")) { mapOf("a" to null) })
  }

  @Test
  fun `对象键序无所谓,数组顺序有所谓`() {
    assertNull(checkGolden(case("""{"a":1,"b":2}""")) { linkedMapOf("b" to 2, "a" to 1) })
    assertNotNull(checkGolden(case("[1,2]")) { listOf(2, 1) })
  }

  @Test
  fun `类型不同要报出来,数字 1 与字符串 "1" 不等`() {
    assertNotNull(checkGolden(case("1")) { "1" })
    assertNotNull(checkGolden(case("\"1\"")) { 1 })
    assertNotNull(checkGolden(case("true")) { "true" })
  }

  // --- diff 可读性 ---------------------------------------------------------

  @Test
  fun `一条用例的多处差异全列出来,带路径`() {
    val failure = checkGolden(case("""{"a":1,"b":{"c":"x"},"d":[1,2]}""")) {
      mapOf("a" to 2, "b" to mapOf("c" to "y"), "d" to listOf(1, 3))
    }
    assertNotNull(failure)
    assertTrue(failure.contains("3 处差异"), failure)
    assertTrue(failure.contains("\$.a"), failure)
    assertTrue(failure.contains("\$.b.c"), failure)
    assertTrue(failure.contains("\$.d[1]"), failure)
  }

  @Test
  fun `长字符串的差异指出首处不同的码元位置`() {
    val expected = "原".repeat(200)
    val actual = expected.substring(0, 120) + "神" + expected.substring(121)
    val failure = checkGolden(case(JsonPrimitive(expected).toString())) { actual }
    assertNotNull(failure)
    assertTrue(failure.contains("首处不同在码元 #120"), failure)
  }

  // --- 抛错形态 ------------------------------------------------------------

  @Test
  fun `期望抛错而实际正常返回 = 不通过`() {
    val failure = checkGolden(case("""{"throws":{"kind":"parse","message":"响应为空","retryable":true}}""")) { "ok" }
    assertNotNull(failure)
    assertTrue(failure.contains("期望抛错"), failure)
  }

  @Test
  fun `期望正常返回而实际抛错 = 不通过`() {
    val failure = checkGolden(case("\"ok\"")) { error("炸了") }
    assertNotNull(failure)
    assertTrue(failure.contains("实际抛了"), failure)
  }

  @Test
  fun `抛错逐字段比 kind message retryable`() {
    val expected = """{"throws":{"kind":"parse","message":"响应为空","retryable":true}}"""
    val describer = GoldenThrowDescriber { error ->
      buildJsonObject {
        put("kind", "parse")
        put("message", error.message ?: "")
        put("retryable", error.message == "响应为空")
      }
    }
    assertNull(checkGolden(case(expected), describer) { throw IllegalStateException("响应为空") })

    // retryable 对不上 = 不通过(它决定反封锁链要不要往下走,ADR-0002)
    val failure = checkGolden(case(expected), describer) { throw IllegalStateException("别的错") }
    assertNotNull(failure)
    assertTrue(failure.contains("throws.retryable"), failure)
    assertTrue(failure.contains("throws.message"), failure)
  }

  @Test
  fun `默认描述器给的是 kind=error 那一档`() {
    assertNull(
      checkGolden(case("""{"throws":{"kind":"error","message":"炸了"}}""")) { error("炸了") },
    )
  }

  @Test
  fun `框架自己出错不会被当成「期望抛错」吞掉`() {
    val failure = checkGolden(case("""{"throws":{"kind":"error","message":"x"}}""")) { c -> c.stringField("nope") }
    assertNotNull(failure)
    assertTrue(failure.contains("对拍框架自己出错"), failure)
  }

  // --- 表驱动跑法 ----------------------------------------------------------

  @Test
  fun `没注册实现的 fn 是失败,不是跳过`() {
    val error = assertFailsWith<AssertionError> {
      runGoldenDomain("entities") {
        fn("unescapeNgaText") { case -> unescapeNgaText(case.inputString()) }
        // 故意不注册 escapeForSubmit
      }
    }
    val message = error.message ?: ""
    assertTrue(message.contains("没有注册实现"), message)
    assertTrue(message.contains("escapeForSubmit"), message)
  }

  @Test
  fun `注册了却一条都没命中的 fn 也是失败(名字打错了)`() {
    val error = assertFailsWith<AssertionError> {
      runGoldenDomain("entities") {
        fn("unescapeNgaText") { case -> unescapeNgaText(case.inputString()) }
        fn("escapeForSubmit") { case -> escapeForSubmit(case.inputString()) }
        fn("escapeForSubmitTypo") { "" }
      }
    }
    assertTrue((error.message ?: "").contains("escapeForSubmitTypo"), error.message ?: "")
  }

  @Test
  fun `失败不是首错即停,失败用例名逐条列出`() {
    val error = assertFailsWith<AssertionError> {
      runGoldenDomain("entities") {
        fn("unescapeNgaText") { "永远不对" }
        fn("escapeForSubmit") { "永远不对" }
      }
    }
    val message = error.message ?: ""
    // entities 有 20 条,除了本来就返回空串的那两条以外全会红
    assertTrue(message.contains("20 条中"), message)
    assertTrue(message.contains("unescape-plain-text"), message)
    assertTrue(message.contains("escape-for-submit-emoji"), message)
  }

  @Test
  fun `不存在的 domain 直接报错,不静默通过`() {
    assertFailsWith<AssertionError> { runGoldenDomain("no-such-domain") { fn("x") { "" } } }
  }

  // --- 索引与语料完整性 ----------------------------------------------------

  @Test
  fun `index_json 里登记的每一条都读得出来`() {
    var loaded = 0
    for (domain in Goldens.domains()) {
      for (case in Goldens.load(domain)) {
        assertTrue(case.fn.isNotEmpty(), "${case.resourcePath} 的 fn 为空")
        loaded++
      }
    }
    assertEquals(Goldens.total(), loaded, "index.json 的 total 与实际读到的条数对不上")
    assertTrue(loaded >= 617, "金样本条数不该变少:$loaded")
  }
}
