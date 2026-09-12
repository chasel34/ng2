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

  @Test
  fun `解析失败抛 parse 错误且可重试`() {
    val error = assertFailsWith<NgaError> { parseNgaJson("<html>你被封了</html>") }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable)
    assertTrue(error.text.startsWith("响应不是合法 JSON"), "实际是:${error.text}")
  }

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

  @Test
  fun `坏字节的响应抛 parse 而不是 server`() {
    val error = assertFailsWith<NgaError> {
      parseNgaJson(captureText("capture-thread-list-414-broken-bytes"), "direct")
    }

    assertEquals(NgaErrorKind.PARSE, error.kind)
    assertTrue(error.retryable)
  }
}
