package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.Goldens
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 手工移植 `src/core/net/fetcher.test.ts` 里「HTTP 状态 × body」那一组用例,
 * 加上 `strategies/attempt.ts` 的「未登录 ⇒ 强制可重试」与「调用方一票否决」两条分支。
 *
 * TS 侧这些断言是穿过整条 fetcher 打出来的(要造假 transport、要 await),
 * 这边被测的是抠出来的纯函数 [classifyHttpResponse],断言直接落在返回值上。
 */
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

  /** HTTP 非 2xx 时 body 仍可能带有效错误信息,所以先解析 body(API 文档 §0.7)。 */
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

  /** 「空」按 JS 的 `trim()` 算,不是 `isBlank()`——NBSP、全角空格都要算空白。 */
  @Test
  fun `非 2xx 且 body 全是空白也按状态码报错`() {
    val error = failed(classifyHttpResponse(502, "  　\n "))

    assertEquals(NgaErrorKind.HTTP, error.kind)
  }

  /** 非 2xx 但 body 有内容只是解析不了 → parse(被封的信号),状态码一并带上。 */
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

  /**
   * ADR-0002 第 6 条:服务端说「未登录」不是语义失败,是这一发请求没被认出身份。
   * 判成不可重试的代价是三重的——链上后面的网页兜底与帖子缓存一个都轮不到、
   * 丢身份的那个组合会被记进成功组合缓存、用户点重试也没用。
   */
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

  /**
   * 调用方的一票否决(ADR-0002 第 1 条):形状不对的响应等同于解析失败,
   * 于是它既不会被当成结果交出去,也不会被 format-rotation 记成「好组合」。
   */
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

  /** bare 接口的顶层就是数据,不该被「既没有 data 也没有 error」挡掉。 */
  @Test
  fun `信封形状透传给 parseNgaJson`() {
    val envelope = ok(
      classifyHttpResponse(200, "{\"code\":0,\"result\":[]}", shape = EnvelopeShape.BARE),
    )

    assertEquals("0", envelope.data!!.jsonObject.getValue("code").jsonPrimitive.content)
  }

  /** 解析器可替换是给票 06 的接缝(网页反解会换一个解析器进来)。 */
  @Test
  fun `解析器可替换`() {
    val stub = NgaEnvelope(root = kotlinx.serialization.json.JsonObject(emptyMap()), data = null)

    assertEquals(stub, ok(classifyHttpResponse(200, "随便什么", parse = { stub })))
  }

  /** 解析器抛的不是 NgaError 时,错误信息退回一句通用的,别把内部异常文案漏给用户。 */
  @Test
  fun `非 NgaError 的异常归为 parse`() {
    val error = failed(
      classifyHttpResponse(200, "x", parse = { throw IllegalStateException("内部细节") }),
    )

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("响应解析失败", error.text)
  }
}
