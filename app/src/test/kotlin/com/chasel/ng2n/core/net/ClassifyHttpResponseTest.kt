package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.Goldens
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ClassifyHttpResponseTest {

  private fun captureText(name: String): String =
    Goldens.load("envelope").first { it.name == name }.stringField("text")

  private fun failed(result: HttpClassification): NgaError = when (result) {
    is HttpClassification.Failed -> result.error
    is HttpClassification.Ok -> fail("期望失败,实际拿到信封:${result.envelope.data}")
  }

  private fun ok(result: HttpClassification): NgaEnvelope = when (result) {
    is HttpClassification.Ok -> result.envelope
    is HttpClassification.Failed -> fail("期望成功,实际:${result.error}")
  }

  @Test
  fun `非 2xx 但 body 有错误信息就报服务端错误`() {
    val error = failed(
      classifyHttpResponse(403, captureText("capture-read-thread-not-found"), via = "direct"),
    )

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertTrue(error.text.contains("找不到主题"))
    assertEquals(false, error.retryable)
  }

  @Test
  fun `非 2xx 但 body 有正常数据时仍然当成功`() {
    val envelope = ok(classifyHttpResponse(500, captureText("capture-ucp-user")))

    assertEquals(
      "BugenZhao",
      envelope.data!!.jsonObject.getValue("0").jsonObject.getValue("username").jsonPrimitive.content,
    )
  }

  @Test
  fun `非 2xx 且 body 为空才用状态码报错`() {
    val error = failed(classifyHttpResponse(502, "", via = "direct"))

    assertEquals(NgaErrorKind.HTTP, error.kind)
    assertEquals(502, error.status)
    assertEquals("HTTP 502", error.text)
    assertTrue(error.retryable)
    assertEquals("direct", error.via)
  }

  @Test
  fun `非 2xx 且 body 全是空白也按状态码报错`() {
    val error = failed(classifyHttpResponse(502, "  　\n "))

    assertEquals(NgaErrorKind.HTTP, error.kind)
  }

  @Test
  fun `非 2xx 且 body 解析不了报 parse 并带上状态码`() {
    val error = failed(classifyHttpResponse(403, "<html>Forbidden</html>", via = "direct"))

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(403, error.status)
    assertTrue(error.retryable)
  }

  @Test
  fun `2xx 但解析不了同样报 parse`() {
    val error = failed(classifyHttpResponse(200, "<html>你被封了</html>"))

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals(200, error.status)
    assertTrue(error.retryable)
  }

  @Test
  fun `未登录强制可重试`() {
    val error = failed(
      classifyHttpResponse(200, "{\"error\":{\"code\":1,\"0\":\"未登录\"},\"time\":1}", via = "direct"),
    )

    assertEquals(NgaErrorKind.SERVER, error.kind, "kind 还是 server:它确实是服务端说的")
    assertTrue(error.retryable, "但要可重试,否则整条链被掐死")
    assertEquals("未登录", error.text)
    assertEquals("1", error.code?.content)
    assertEquals("direct", error.via)
  }

  @Test
  fun `其余服务端语义错误照旧不可重试`() {
    val error = failed(classifyHttpResponse(200, "{\"error\":{\"code\":403,\"0\":\"找不到主题\"}}"))

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertEquals(false, error.retryable)
  }

  @Test
  fun `假错误白名单短路成成功`() {
    val envelope = ok(classifyHttpResponse(200, captureText("capture-ucp-not-found")))

    assertEquals("找不到用户", envelope.fakeError?.message)
    assertEquals(null, envelope.data)
  }

  @Test
  fun `一票否决把能解析的响应也判成 parse`() {
    val error = failed(
      classifyHttpResponse(
        status = 200,
        bodyText = "{\"data\":{\"0\":\"ok\"}}",
        via = "format-rotation",
        validate = { "不是主题列表" },
      ),
    )

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("不是主题列表", error.text)
    assertEquals(200, error.status)
    assertTrue(error.retryable)
  }

  @Test
  fun `一票否决返回 null 就是认可`() {
    var seen: NgaEnvelope? = null
    val envelope = ok(
      classifyHttpResponse(200, "{\"data\":{\"0\":\"ok\"}}", validate = { seen = it; null }),
    )

    assertEquals(envelope, seen)
  }

  @Test
  fun `信封形状透传给 parseNgaJson`() {
    val envelope = ok(
      classifyHttpResponse(200, "{\"code\":0,\"result\":[]}", shape = EnvelopeShape.BARE),
    )

    assertEquals("0", envelope.data!!.jsonObject.getValue("code").jsonPrimitive.content)
  }

  @Test
  fun `解析器可替换`() {
    val stub = NgaEnvelope(root = kotlinx.serialization.json.JsonObject(emptyMap()), data = null)

    assertEquals(stub, ok(classifyHttpResponse(200, "随便什么", parse = { stub })))
  }

  @Test
  fun `非 NgaError 的异常归为 parse`() {
    val error = failed(
      classifyHttpResponse(200, "x", parse = { throw IllegalStateException("内部细节") }),
    )

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("响应解析失败", error.text)
  }
}
