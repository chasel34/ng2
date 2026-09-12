package com.chasel.ng2n.data.filters

import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterSubject
import com.chasel.ng2n.core.local.MAX_REGEX_INPUT_LENGTH
import com.chasel.ng2n.core.local.MAX_REGEX_PATTERN_LENGTH
import com.chasel.ng2n.core.local.MAX_RULE_VALUE_LENGTH
import com.chasel.ng2n.core.local.createFilterRule
import com.chasel.ng2n.core.local.matchFilterRules
import com.chasel.ng2n.core.local.validateFilterRule
import com.chasel.ng2n.data.settings.sanitizeFilterRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.chasel.ng2n.core.local.FilterRule as CoreRule
import com.chasel.ng2n.core.local.FilterRuleKind as CoreKind
import com.chasel.ng2n.core.local.FilterRuleOrigin as CoreOrigin
import com.chasel.ng2n.data.settings.FilterRule as StoredRule

class FilterMappingTest {

  private val rule = createFilterRule(
    FilterRuleInput(CoreKind.KEYWORD, "内部消息"),
    nowSeconds = 1_700_000_000,
  )

  @Test
  fun `存储形态与判定形态往返一趟一个字段都不掉`() {
    val back = rule.toStored().toCore()
    assertEquals(rule, back)
  }

  @Test
  fun `带 uid 的用户规则往返也不掉 uid`() {
    val user = createFilterRule(FilterRuleInput(CoreKind.USER, "张三", uid = 42L), 100)
    val back = assertNotNull(user.toStored().toCore())
    assertEquals(42L, back.uid)
    assertEquals(CoreKind.USER, back.kind)
    assertEquals(CoreOrigin.LOCAL, back.origin)
  }

  @Test
  fun `认不出的 kind 在桥这里跳过 而不是把整张表拖垮`() {
    val broken = StoredRule(id = "local:???:x", kind = "???", origin = "local", value = "x")
    assertNull(broken.toCore())

    val good = rule.toStored()
    assertEquals(listOf(good), sanitizeFilterRules(listOf(broken, good)))
  }

  @Test
  fun `origin 缺失的老存档按本地规则读回来`() {
    val legacy = StoredRule(id = "local:keyword:x", kind = "keyword", origin = "", value = "x")
    assertEquals(CoreOrigin.LOCAL, assertNotNull(legacy.toCore()).origin)
  }

  @Test
  fun `P3-05 嵌套量词在存之前就被拦住 存不进本地表`() {
    val message = validateFilterRule(FilterRuleInput(CoreKind.KEYWORD, "(a+)+\$", regex = true))
    assertTrue(
      message?.startsWith("正则表达式不合法") == true,
      "对话框应当拿到一句拒绝理由,实际是 $message",
    )
    assertNull(validateFilterRule(FilterRuleInput(CoreKind.KEYWORD, "(a+)+\$")))
  }

  @Test
  fun `P3-05 超长规则文本存不进去`() {
    val long = "广".repeat(MAX_RULE_VALUE_LENGTH + 1)
    assertEquals(
      "关键词最长 $MAX_RULE_VALUE_LENGTH 个字符",
      validateFilterRule(FilterRuleInput(CoreKind.KEYWORD, long)),
    )
  }

  @Test
  fun `P3-05 就算病态规则已经躺在存档里 判定这一层也不会卡死或抛`() {
    val stored = StoredRule(
      id = "local:keyword:(?:a|aa)+b",
      kind = "keyword",
      origin = "local",
      value = "(?:a|aa)+b",
      regex = true,
    )
    val rules = listOf(assertNotNull(stored.toCore()))
    assertNull(matchFilterRules(rules, FilterSubject(content = "a".repeat(4_000))))

    val huge = StoredRule(
      id = "local:keyword:long",
      kind = "keyword",
      origin = "local",
      value = "a".repeat(MAX_REGEX_PATTERN_LENGTH + 1),
      regex = true,
    )
    assertNull(matchFilterRules(listOf(assertNotNull(huge.toCore())), FilterSubject(content = "aaa")))
  }

  @Test
  fun `P3-05 正常正则规则照样命中 上限没有误伤`() {
    val stored = createFilterRule(
      FilterRuleInput(CoreKind.KEYWORD, "^\\[水\\]", regex = true),
      100,
    ).toStored()
    val rules = listOf<CoreRule>(assertNotNull(stored.toCore()))
    assertNotNull(matchFilterRules(rules, FilterSubject(title = "[水]今天吃什么")))
    assertNull(matchFilterRules(rules, FilterSubject(title = "今天吃什么")))
    assertNotNull(
      matchFilterRules(rules, FilterSubject(title = "[水]" + "x".repeat(MAX_REGEX_INPUT_LENGTH - 10))),
    )
  }
}
