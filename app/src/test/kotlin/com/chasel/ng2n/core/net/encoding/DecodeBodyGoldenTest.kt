package com.chasel.ng2n.core.net.encoding

import com.chasel.ng2n.core.net.gbkBytes
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class DecodeBodyGoldenTest {

  @Test
  fun `decode-body 金样本全量对拍`() = runGoldenDomain("decode-body") {
    fn("parseCharset") { case -> parseCharset(case.stringFieldOrNull("contentType")) }
    fn("decodeResponseBody") { case ->
      decodeResponseBody(case.bytesField(), case.stringFieldOrNull("contentType"))
    }
  }

  @Test
  fun `误声明 UTF-8 的 GBK 响应仍能解出中文`() {
    val text = "iOS客户端闪退问题，请下载恩基爱社区app。网事杂谈 [股市]技术分析"
    for (charset in listOf("UTF-8", "utf8", "unicode-1-1-utf-8")) {
      assertEquals(text, decodeResponseBody(gbkBytes(text), "text/html; charset=$charset"))
    }
  }

  @Test
  fun `合法 UTF-8 中的替换字符和 emoji 不应触发 GBK 回退`() {
    val text = "中文�😀"
    assertEquals(text, decodeResponseBody("\uFEFF$text".toByteArray(), "text/json; charset=UTF-8"))
  }

  @Test
  fun `误声明 UTF-8 且两种解码都有损时选择损失更少的结果`() {
    val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xfd.toByte())
    assertEquals("�", decodeResponseBody(bytes, "text/html; charset=UTF-8"))
    assertEquals("�", decodeResponseBody(byteArrayOf(0xff.toByte()), "text/html; charset=UTF-8"))
  }
}
