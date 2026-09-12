package com.chasel.ng2n.core.net.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlScanTest {

  @Test
  fun `实参里的括号与引号不算数,按顶层逗号切`() {
    val calls = findCalls("x.f( 1,\$('a'),')',\"(\",null )", "x.f(")

    assertEquals(1, calls.size)
    assertEquals(
      listOf(
        JsArgument.Num(1.0),
        JsArgument.Expression("\$('a')"),
        JsArgument.Str(")"),
        JsArgument.Str("("),
        JsArgument.Null,
      ),
      calls[0].args,
    )
  }

  @Test
  fun `转义引号不会提前收尾`() {
    val calls = findCalls("x.f('it\\'s )', 2)", "x.f(")

    assertEquals(JsArgument.Str("it's )"), calls[0].args[0])
    assertEquals(JsArgument.Num(2.0), calls[0].args[1])
  }

  @Test
  fun `多处调用按出现顺序全找出来`() {
    val first = findCalls("a.f(1) b.g(2) a.f(3)", "a.f(").map { it.args[0] }

    assertEquals(listOf(JsArgument.Num(1.0), JsArgument.Num(3.0)), first)
  }

  @Test
  fun `只认 dollar 括号引号 id 这一种,别的实参一律 null`() {
    assertEquals("postcontent7", elementIdOf(JsArgument.Expression("\$('postcontent7')")))
    assertNull(elementIdOf(JsArgument.Null))
    assertNull(elementIdOf(JsArgument.Str("postcontent7")))
  }

  @Test
  fun `同名标签嵌套时找到对的那个收尾`() {
    assertEquals("前<span>里</span>后", innerHtmlOf("<div><p id='c'>前<span>里</span>后</p></div>", "c"))
    assertEquals("a<span>b</span>c", innerHtmlOf("<span id='c'>a<span>b</span>c</span>d", "c"))
  }

  @Test
  fun `自闭合标签不加深度(正文里的换行全是 br)`() {
    assertEquals("一<br/>二", innerHtmlOf("<p id='c'>一<br/>二</p>", "c"))
  }

  @Test
  fun `实体不解码,与 JSON 接口的 content 同口径`() {
    assertEquals("a&amp;b&lt;c", innerHtmlOf("<p id='c'>a&amp;b&lt;c</p>", "c"))
  }

  @Test
  fun `id 前缀相同的元素不会张冠李戴`() {
    val html = "<p id='postcontent1'>一</p><p id='postcontent12'>十二</p>"

    assertEquals("一", innerHtmlOf(html, "postcontent1"))
    assertEquals("十二", innerHtmlOf(html, "postcontent12"))
  }

  @Test
  fun `空元素与找不到的 id`() {
    assertEquals("", innerHtmlOf("<h3 id='c'></h3>", "c"))
    assertNull(innerHtmlOf("<h3 id='c'></h3>", "nope"))
  }

  @Test
  fun `键不带引号的 JS 对象数组(附件表就是这个形态)`() {
    val parsed = parseObjectLiterals("[{aid:'',url:'a.jpg',thumb:'56'},{url:'b.jpg',size:101}]")

    assertEquals(
      listOf(
        mapOf("aid" to "", "url" to "a.jpg", "thumb" to "56"),
        mapOf("url" to "b.jpg", "size" to "101"),
      ),
      parsed,
    )
  }

  @Test
  fun `匹配到空串与整支没匹配是两回事`() {
    assertEquals(listOf(mapOf("aid" to "", "n" to "-3")), parseObjectLiterals("{aid:'',n:-3}"))
  }

  @Test
  fun `balancedSlice 跳过字符串里的括号`() {
    val slice = balancedSlice("f({a:'(',b:')'})", 0, '{', '}')

    assertEquals("a:'(',b:')'", slice?.body)
  }

  @Test
  fun `readIntVariable 认 parseInt 包裹与裸赋值`() {
    assertEquals(45150945L, readIntVariable("__CURRENT_TID=45150945,", "__CURRENT_TID"))
    assertEquals(10000001L, readIntVariable("var __CURRENT_UID = parseInt('10000001',10),", "__CURRENT_UID"))
    assertNull(readIntVariable("nothing here", "__CURRENT_TID"))
  }

  @Test
  fun `readStringVariable 认转义引号`() {
    assertEquals("img.nga.cn", readStringVariable("__ATTACH_BASE_VIEW = 'img.nga.cn';", "__ATTACH_BASE_VIEW"))
    assertEquals("it's", readStringVariable("""x = 'it\'s';""", "x"))
  }

  @Test
  fun `readMarkedSection 取注释标记之间那段`() {
    assertEquals("2048", readMarkedSection("<!--msgcodestart-->2048<!--msgcodeend-->", "msgcode"))
    assertNull(readMarkedSection("<!--msgcodestart-->2048", "msgcode"))
  }
}
