package com.chasel.ng2n.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 错误分类与可重试语义。金样本只覆盖了 `errors.ts` 的四个纯函数,
 * **`NgaError` 自身的 `retryable` 推导一条都没进去**(它是构造出来的,不是「输入→输出」),
 * 而那正是反封锁链的开关(ADR-0002 第 6/7 条)。这里逐条钉。
 */
class ErrorsTest {

  private fun root(text: String) = Json.parseToJsonElement(text)

  // --- retryable 的默认推导 -------------------------------------------------

  /**
   * 「除了服务端语义错误,其余失败都值得换下一档策略试试」。
   *
   * 比 MNGA 的判据多放了 `network`:本项目的链末端是帖子缓存与网页兜底,
   * 断网时正该落到缓存那一档,而不是在第一档就把错误抛给用户。
   */
  @Test
  fun `默认可重试性只看 kind 是不是 server`() {
    val expected = mapOf(
      NgaErrorKind.NETWORK to true,
      NgaErrorKind.HTTP to true,
      NgaErrorKind.PARSE to true,
      NgaErrorKind.SERVER to false,
      NgaErrorKind.UNAVAILABLE to true,
    )
    // 漏一个 kind 就是漏一条链路判据,所以枚举必须被完整覆盖
    assertEquals(NgaErrorKind.entries.toSet(), expected.keys)

    for ((kind, retryable) in expected) {
      assertEquals(retryable, NgaError(kind, "x").retryable, "kind=$kind")
    }
  }

  /** 调用方主动取消的请求会显式传 `retryable = false`——别让链继续往下试。 */
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

  // --- 白名单 ---------------------------------------------------------------

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

  /**
   * 「未登录」说的不是「你要的这条数据有问题」,而是「这一发请求没被认出身份」
   * ——cookie jar 按域名存,换个组合往往就好了(ADR-0002 第 6 条)。
   * 白名单目前只有这一条,**加词条前先问「换个组合有没有可能改变这个结果」**。
   */
  @Test
  fun `身份级错误白名单`() {
    assertTrue(isAuthLevelServerError("未登录"))
    assertTrue(isAuthLevelServerError("1:未登录"), "服务端会带 code 前缀")
    assertFalse(isAuthLevelServerError("找不到主题"))
    assertFalse(isAuthLevelServerError("你没有登录或者登录信息已过期"), "措辞不同就不在白名单里")
  }

  // --- extractServerError ---------------------------------------------------

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

  /** `code` 是 `string | number`:`"?"` 与 `403` 必须分得开,收成 String 会把两者拍平。 */
  @Test
  fun `code 的字符串形态与数字形态不混`() {
    val numeric = extractServerError(root("{\"error\":{\"code\":51,\"0\":\"x\"}}"))!!.code
    val fallback = extractServerError(root("{\"error\":{\"0\":\"x\"}}"))!!.code

    assertFalse(numeric.isString)
    assertTrue(fallback.isString)
    assertEquals("51", numeric.content)
    assertEquals("?", fallback.content)
  }

  /** 布尔 / null 形态的 `code` 不算 code(TS 的 `typeof` 只认 string 与 number)。 */
  @Test
  fun `认不出的 code 退回问号`() {
    assertEquals("?", extractServerError(root("{\"error\":{\"code\":true,\"0\":\"x\"}}"))!!.code.content)
    assertEquals("?", extractServerError(root("{\"error\":{\"code\":null,\"0\":\"x\"}}"))!!.code.content)
  }

  /**
   * 数组形态(`{"error":["访问速度过快"]}`):TS 的 `isRecord` 把数组排除在外,
   * 不单独认一下的话服务端说的原话会被整条丢掉,用户看到的是「响应里没有 data」
   * 这种毫无信息量的话(2026-08-13,「版块全空」排查)。
   */
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

  /**
   * 空数组不当错误:PHP 的空数组序列化出来就是 `[]`,和「这个字段没内容」分不开;
   * 而对象形态那一档(有 error 对象但没有可读信息)仍然算错误——那是明确的结构。
   */
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

  /** 字符串形态的 error 也要剥标签;剥完只剩空的仍然算错误——空说明比误判成功强。 */
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
