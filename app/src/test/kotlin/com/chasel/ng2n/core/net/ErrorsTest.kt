package com.chasel.ng2n.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorsTest {

  private fun root(text: String) = Json.parseToJsonElement(text)

  @Test
  fun `默认可重试性只看 kind 是不是 server`() {
    val expected = mapOf(
      NgaErrorKind.NETWORK to true,
      NgaErrorKind.HTTP to true,
      NgaErrorKind.PARSE to true,
      NgaErrorKind.SERVER to false,
      NgaErrorKind.UNAVAILABLE to true,
    )
    assertEquals(NgaErrorKind.entries.toSet(), expected.keys)

    for ((kind, retryable) in expected) {
      assertEquals(retryable, NgaError(kind, "x").retryable, "kind=$kind")
    }
  }

  @Test
  fun `显式传的 retryable 盖过默认推导`() {
    assertFalse(NgaError(NgaErrorKind.NETWORK, "已取消", retryable = false).retryable)
    assertTrue(NgaError(NgaErrorKind.SERVER, "未登录", retryable = true).retryable)
  }

  @Test
  fun `kind 的字面量与 TS 的联合类型一一对应`() {
    assertEquals(
      listOf("network", "http", "parse", "server", "unavailable"),
      NgaErrorKind.entries.map { it.wire },
    )
  }

  @Test
  fun `假错误白名单按子串匹配`() {
    for (message in listOf("完毕", "没找到", "没有符合条件的结果", "今天已经签到", "找不到用户")) {
      assertTrue(isFakeError(message), message)
    }
    assertTrue(isFakeError("发贴完毕"))
    assertTrue(isFakeError("操作完毕，正在跳转"))
  }

  @Test
  fun `真错误不在假错误白名单里`() {
    assertFalse(isFakeError("找不到主题"))
    assertFalse(isFakeError("未登录"))
    assertFalse(isFakeError("您没有权限进行此操作"))
  }

  @Test
  fun `身份级错误白名单`() {
    assertTrue(isAuthLevelServerError("未登录"))
    assertTrue(isAuthLevelServerError("1:未登录"), "服务端会带 code 前缀")
    assertFalse(isAuthLevelServerError("找不到主题"))
    assertFalse(isAuthLevelServerError("你没有登录或者登录信息已过期"), "措辞不同就不在白名单里")
  }

  @Test
  fun `error 的对象形态`() {
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "未登录"),
      extractServerError(root("{\"error\":{\"0\":\"未登录\"}}")),
    )
    assertEquals(
      NgaServerError(JsonPrimitive(403), "找不到主题"),
      extractServerError(root("{\"error\":{\"code\":403,\"0\":\"找不到主题\"}}")),
    )
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "a；b"),
      extractServerError(root("{\"error\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"\"}}")),
    )
  }

  @Test
  fun `code 的字符串形态与数字形态不混`() {
    val numeric = extractServerError(root("{\"error\":{\"code\":51,\"0\":\"x\"}}"))!!.code
    val fallback = extractServerError(root("{\"error\":{\"0\":\"x\"}}"))!!.code

    assertFalse(numeric.isString)
    assertTrue(fallback.isString)
    assertEquals("51", numeric.content)
    assertEquals("?", fallback.content)
  }

  @Test
  fun `认不出的 code 退回问号`() {
    assertEquals("?", extractServerError(root("{\"error\":{\"code\":true,\"0\":\"x\"}}"))!!.code.content)
    assertEquals("?", extractServerError(root("{\"error\":{\"code\":null,\"0\":\"x\"}}"))!!.code.content)
  }

  @Test
  fun `error 的数组形态`() {
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "访问速度过快"),
      extractServerError(root("{\"error\":[\"访问速度过快\"]}")),
    )
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "甲；乙"),
      extractServerError(root("{\"error\":[\"甲\",\"\",\"乙\",1]}")),
    )
  }

  @Test
  fun `空数组不是错误,空对象是`() {
    assertNull(extractServerError(root("{\"data\":{\"0\":1},\"error\":[]}")))
    assertEquals(
      NgaServerError(JsonPrimitive(7), "未知错误（code=7）"),
      extractServerError(root("{\"error\":{\"code\":7}}")),
    )
  }

  @Test
  fun `没有 error 就是 null`() {
    assertNull(extractServerError(root("{\"data\":{},\"time\":1}")))
    assertNull(extractServerError(root("\"not an object\"")))
    assertNull(extractServerError(root("{\"error\":null}")))
    assertNull(extractServerError(null))
  }

  @Test
  fun `error 的字符串形态`() {
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "未登录"),
      extractServerError(root("{\"error\":\"<b>未登录</b>\"}")),
    )
    assertEquals(
      NgaServerError(JsonPrimitive("?"), "未知错误（code=?）"),
      extractServerError(root("{\"error\":\"<br/>\"}")),
    )
    assertNull(extractServerError(root("{\"error\":\"\"}")))
  }
}
