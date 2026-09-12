package com.chasel.ng2n.core.net

import com.chasel.ng2n.golden.Goldens
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手工移植 `src/core/net/envelope.test.ts` 里的控制流与抛错分支。
 *
 * 金样本已经把「入参 → 出参」逐条锁住了,这里补的是它表达不了的两件事:
 * 抛出来的是不是 [NgaError] 这个**类型**(下游靠 `is NgaError` 分流),
 * 以及真实抓包解出来的结构对不对。
 */
class EnvelopeTest {

  private fun captureText(name: String): String =
    Goldens.load("envelope").first { it.name == name }.stringField("text")

  @Test
  fun `取出 data 与 time`() {
    val envelope = parseNgaJson("{\"data\":{\"0\":\"ok\"},\"time\":1786111705}")

    assertEquals("ok", envelope.data!!.jsonObject.getValue("0").jsonPrimitive.content)
    assertEquals(1786111705L, envelope.time)
    assertNull(envelope.fakeError)
  }

  /**
   * 以前这里把顶层当 data,于是任何一个陌生 JSON 都成了「合法的空数据」,
   * 一路走到 UI 变成「这个版块还没有主题」,还会被反封锁链记进成功组合缓存
   * (2026-08-13,「版块全空」排查;ADR-0002 第 1 条)。
   */
  @Test
  fun `顶层既没有 data 也没有 error 时默认报解析错`() {
    val error = assertFailsWith<NgaError> {
      parseNgaJson("{\"code\":0,\"msg\":\"\",\"result\":[]}", "direct")
    }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable, "解析错要可重试,链才会换下一个组合")
    assertEquals("direct", error.via)
  }

  @Test
  fun `调用方显式声明 bare 时顶层才当 data`() {
    val envelope = parseNgaJson("{\"code\":0,\"msg\":\"\",\"result\":[]}", null, EnvelopeShape.BARE)

    assertEquals("0", envelope.data!!.jsonObject.getValue("code").jsonPrimitive.content)
  }

  @Test
  fun `有 data 壳的响应不受 bare 影响`() {
    val envelope = parseNgaJson("{\"data\":{\"0\":\"ok\"}}", null, EnvelopeShape.BARE)

    assertEquals("ok", envelope.data!!.jsonObject.getValue("0").jsonPrimitive.content)
  }

  @Test
  fun `真错误抛 server 错误且不重试`() {
    val error = assertFailsWith<NgaError> {
      parseNgaJson(captureText("capture-read-thread-not-found"), "direct")
    }

    assertEquals(NgaErrorKind.SERVER, error.kind)
    assertTrue(error.text.contains("找不到主题"))
    assertEquals(false, error.retryable)
    assertEquals("direct", error.via)
  }

  @Test
  fun `假错误当成功返回,但把错误信息留给调用方判空`() {
    val envelope = parseNgaJson(captureText("capture-ucp-not-found"))

    assertEquals("找不到用户", envelope.fakeError?.message)
    assertNull(envelope.data, "只有 error 的响应,data 必须是「没有」而不是空对象")
  }

  /** 解析失败 ≈ 被封,必须可重试,否则链上后面的网页兜底与帖子缓存一个都轮不到。 */
  @Test
  fun `解析失败抛 parse 错误且可重试`() {
    val error = assertFailsWith<NgaError> { parseNgaJson("<html>你被封了</html>") }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable)
    assertTrue(error.text.startsWith("响应不是合法 JSON"), "实际是:${error.text}")
  }

  /**
   * `Json.parseToJsonElement` 比 `JSON.parse` 松:整页 HTML 会被它当成一个不带引号的原语
   * 收下,于是「洗不成 JSON」这条信号会被降级成「顶层不是对象」。两者得分得开。
   */
  @Test
  fun `顶层是合法 JSON 但不是对象时报的是另一句话`() {
    val error = assertFailsWith<NgaError> { parseNgaJson("[1,2,3]") }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("响应顶层不是对象", error.text)
  }

  @Test
  fun `空响应也算 parse 错误`() {
    val error = assertFailsWith<NgaError> { parseNgaJson("") }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertEquals("响应为空", error.text)
  }

  @Test
  fun `真实抓包 · 通知接口拿到 data`() {
    val envelope = parseNgaJson(captureText("capture-noti-empty"))

    assertEquals("", envelope.data!!.jsonObject.getValue("0").jsonPrimitive.content)
  }

  @Test
  fun `真实抓包 · 用户资料`() {
    val user = parseNgaJson(captureText("capture-ucp-user")).data!!.jsonObject.getValue("0").jsonObject

    assertEquals("41417929", user.getValue("uid").jsonPrimitive.content)
    assertEquals("BugenZhao", user.getValue("username").jsonPrimitive.content)
  }

  @Test
  fun `真实抓包 · 主题列表`() {
    val data = parseNgaJson(captureText("capture-thread-list")).data!!.jsonObject

    assertEquals("原神", data.getValue("__F").jsonObject.getValue("name").jsonPrimitive.content)
    assertTrue(data.getValue("__T").jsonObject.size > 10)
  }

  @Test
  fun `真实抓包 · read_php 的 lite=js 前缀被剥掉后能解析`() {
    val data = parseNgaJson(captureText("capture-read-thread-jsvar")).data!!.jsonObject

    assertNotNull(data["__R"])
  }

  /**
   * fid=414:服务端下发的字节本身就坏,洗完仍然不是合法 JSON。
   * **必须抛 parse(可重试)**,链才会轮到 `__output=11` 那一档——它是另一个序列化器,
   * 只有它救得了这个版块(ADR-0002 第 8 条)。
   */
  @Test
  fun `坏字节的响应抛 parse 而不是 server`() {
    val error = assertFailsWith<NgaError> {
      parseNgaJson(captureText("capture-thread-list-414-broken-bytes"), "direct")
    }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable)
  }
}
