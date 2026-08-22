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

/**
 * 票 17b:屏蔽规则屏读写这条路上的两件事。
 *
 * 1. **存储形态 ↔ 判定形态的桥**([toCore] / [toStored])—— 票 10 与票 14 各落了一份
 *    `FilterRule`,屏幕这一层要在 DataStore 边界上换形状,换错了就是「加了规则不生效」。
 * 2. **P3-05 的写入路径**:对话框读的必须是 `core/local` 那份带资源上限的
 *    `validateFilterRule`,而不是 `data/settings` 里那份没有上限的同名函数。
 */
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

    // 票 14 的落盘容错也认同一条口径:坏条目滤掉,好条目留着
    val good = rule.toStored()
    assertEquals(listOf(good), sanitizeFilterRules(listOf(broken, good)))
  }

  @Test
  fun `origin 缺失的老存档按本地规则读回来`() {
    // 本地表里存的本来就只有本地规则,origin 是后加的字段
    val legacy = StoredRule(id = "local:keyword:x", kind = "keyword", origin = "", value = "x")
    assertEquals(CoreOrigin.LOCAL, assertNotNull(legacy.toCore()).origin)
  }

  // --- P3-05:对话框走的是带上限的那一份校验(RN 版没有,移植时修)-----------------

  @Test
  fun `P3-05 嵌套量词在存之前就被拦住 存不进本地表`() {
    val message = validateFilterRule(FilterRuleInput(CoreKind.KEYWORD, "(a+)+\$", regex = true))
    assertTrue(
      message?.startsWith("正则表达式不合法") == true,
      "对话框应当拿到一句拒绝理由,实际是 $message",
    )
    // 同一个串当**普通子串**用是合法的:上限只针对开了正则的那一档
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
    // 老版本存下来的 / 手改过存档的:读回来照样进判定,这时只剩匹配期的那道闸
    val stored = StoredRule(
      id = "local:keyword:(?:a|aa)+b",
      kind = "keyword",
      origin = "local",
      value = "(?:a|aa)+b",
      regex = true,
    )
    val rules = listOf(assertNotNull(stored.toCore()))
    // 这条在 RN 版会把 UI 线程跑到天荒地老;这里按「不命中」收场
    assertNull(matchFilterRules(rules, FilterSubject(content = "a".repeat(4_000))))

    // pattern 本身超长的那一档连编译都不做
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
    // 输入截断只砍超出 MAX_REGEX_INPUT_LENGTH 的部分,之内的一律看得到
    assertNotNull(
      matchFilterRules(rules, FilterSubject(title = "[水]" + "x".repeat(MAX_REGEX_INPUT_LENGTH - 10))),
    )
  }
}
