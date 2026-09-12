package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.Goldens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手工移植 `src/core/net/sanitize.test.ts` 里**金样本装不下**的那些断言。
 *
 * 金样本锁的是「洗完的文本逐字符相等」;这里锁的是文本之外的东西:
 * 洗完到底能不能 `JSON.parse`、洗完之后某个字段还在不在。
 * 两边都要,少一边就会出现「字符串对上了但解析不了」或者「解析得了但字段被吃掉了」。
 */
class SanitizeTest {

  private val json = Json

  private fun goldenInput(name: String): String =
    Goldens.load("sanitize").first { it.name == name }.inputString()

  private fun sanitizeAndParse(raw: String): JsonObject =
    json.parseToJsonElement(sanitizeNgaJson(raw)).jsonObject

  /**
   * 票面要求的专门用例:**`alterinfo` 不删**。
   *
   * 上游参考实现(Android `ArticleConvertFactory` 第 5 步)把 `"alterinfo":"[xxx] "` 整段删掉,
   * 本实现刻意不删——真身是 `[E<时间戳> <编辑人 uid> <编辑人名>]<TAB>`,唯一「坏」的地方是
   * 结尾那个裸 TAB,第 7 步转义掉就是合法 JSON。删了等于把「本楼被编辑过」连同编辑人一起丢掉,
   * 详情页再也认不出编辑标记。
   */
  @Test
  fun `alterinfo 保留,裸 TAB 只被转义不被删`() {
    val parsed = sanitizeAndParse("{\"pid\":1,\"alterinfo\":\"[E1748252294 0 0]\t\",\"lou\":3}")

    assertEquals("[E1748252294 0 0]\t", parsed.getValue("alterinfo").jsonPrimitive.content)
    assertEquals("1", parsed.getValue("pid").jsonPrimitive.content)
    assertEquals("3", parsed.getValue("lou").jsonPrimitive.content)
  }

  /** 真机抓包里每一条楼层都带 `alterinfo`,整段删的话这个字段会从 `__R` 里消失。 */
  @Test
  fun `真实抓包的 alterinfo 字段洗完还在`() {
    val parsed = sanitizeAndParse(goldenInput("capture-read-thread-jsvar"))
    val floors = parsed.getValue("data").jsonObject.getValue("__R").jsonObject

    assertTrue(floors.isNotEmpty(), "抓包里应当有楼层")
    floors.forEach { (lou, floor) ->
      assertTrue(floor.jsonObject.containsKey("alterinfo"), "第 $lou 楼的 alterinfo 被吃掉了")
    }
  }

  @Test
  fun `六步叠在一起洗完是合法 JSON`() {
    val raw = "window.script_muti_get_var_store=({\"data\":{0:{\"pid\":9," +
      "\"alterinfo\":\"[E1 0 0]\t\",\"content\":+7,\"subject\":012,\"note\":\"裸控制符\"}}});" +
      "/*error fill content xxx"

    val floor = sanitizeAndParse(raw).getValue("data").jsonObject.getValue("0").jsonObject

    assertEquals("9", floor.getValue("pid").jsonPrimitive.content)
    assertEquals("[E1 0 0]\t", floor.getValue("alterinfo").jsonPrimitive.content)
    // 非法数字转成了字符串(带引号),不是数字
    assertTrue(floor.getValue("content").jsonPrimitive.isString)
    assertEquals("+7", floor.getValue("content").jsonPrimitive.content)
    assertEquals("012", floor.getValue("subject").jsonPrimitive.content)
  }

  /** 6 份真机抓包洗完都要能 `JSON.parse`——金样本只比字符串,不替这一步背书。 */
  @Test
  fun `真实抓包洗完都是合法 JSON`() {
    val captures = Goldens.load("sanitize").filter { it.name.startsWith("capture-") }
    assertEquals(6, captures.size, "抓包样本条数变了,先确认金样本是不是重导过")

    for (case in captures) {
      val parsed = json.parseToJsonElement(sanitizeNgaJson(case.inputString()))
      assertTrue(parsed is JsonObject, "${case.name} 洗完不是 JSON 对象")
    }
  }

  @Test
  fun `read_php 的 lite=js 前缀被剥掉`() {
    val raw = goldenInput("capture-read-thread-jsvar")

    assertTrue(raw.startsWith("window.script_muti_get_var_store="), "样本本身就该带前缀")
    assertTrue(sanitizeNgaJson(raw).startsWith("{"), "剥完应当直接是对象")
  }

  /**
   * 正文里出现 `{12:` 时用裸正则会把用户内容改坏,反而解析失败——那会被误判成被封而
   * 触发整条反封锁链(ADR-0002)。所以第 6 步必须是带字符串状态的扫描。
   */
  @Test
  fun `字符串里长得像整数 key 的内容不能被改`() {
    val parsed = sanitizeAndParse("{\"content\":\"看这段代码 {12:34} 还有 ,56: 这种\"}")

    assertEquals("看这段代码 {12:34} 还有 ,56: 这种", parsed.getValue("content").jsonPrimitive.content)
  }

  @Test
  fun `合法数字不动`() {
    val parsed = sanitizeAndParse("{\"content\":123,\"subject\":0,\"author\":-4}")

    assertTrue(!parsed.getValue("content").jsonPrimitive.isString)
    assertEquals("123", parsed.getValue("content").jsonPrimitive.content)
    assertEquals("0", parsed.getValue("subject").jsonPrimitive.content)
    assertEquals("-4", parsed.getValue("author").jsonPrimitive.content)
  }

  /** `lite=js` 实际会返回 `=({…});` 这种形态,§0.6 的清单里没有这一条。 */
  @Test
  fun `去掉赋值残留的外层括号与结尾分号`() {
    assertEquals("{\"data\":1}", sanitizeNgaJson("window.script_muti_get_var_store=({\"data\":1});"))
    assertEquals("{\"data\":1}", sanitizeNgaJson("({\"data\":1}) ; ;"))
  }

  /** `lite=htmljs`:整页 HTML 里只留 `<script>` 那段。 */
  @Test
  fun `htmljs 的 HTML 外壳只留 script 里的那段`() {
    val raw = "<html><body><script>window.script_muti_get_var_store=" +
      "{\"data\":{\"__MESSAGE\":{\"0\":0}}};</script></body></html>"

    assertEquals("{\"data\":{\"__MESSAGE\":{\"0\":0}}}", sanitizeNgaJson(raw))
  }
}
