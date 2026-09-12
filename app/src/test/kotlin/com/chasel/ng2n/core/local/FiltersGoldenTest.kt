package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiltersGoldenTest {

  @Test
  fun `filters 金样本全量对拍`() = runGoldenDomain("filters") {
    fn("normalizeRuleValue") { case ->
      normalizeRuleValue(case.stringField("value"), case.booleanField("regex"))
    }
    fn("filterRuleId") { case ->
      filterRuleId(
        FilterRuleOrigin.of(case.stringField("origin")),
        FilterRuleKind.of(case.stringField("kind")),
        case.stringField("value"),
      )
    }
    fn("topicCategories") { case -> topicCategories(case.inputString()) }
    fn("validateFilterRule") { case -> validateFilterRule(case.ruleInput()) }
    fn("createFilterRule") { case ->
      createFilterRule(case.field("input").jsonObject.toRuleInput(), case.longField("nowSeconds"))
        .toGoldenMap()
    }
    fn("upsertFilterRule") { case ->
      upsertFilterRule(case.rules(), case.field("rule").jsonObject.toRule()).map { it.toGoldenMap() }
    }
    fn("removeFilterRule") { case ->
      removeFilterRule(case.rules(), case.stringField("id")).map { it.toGoldenMap() }
    }
    fn("matchFilterRules") { case ->
      matchFilterRules(case.rules(), case.field("subject").jsonObject.toSubject())?.toGoldenMap()
    }
    fn("filterMatchText") { case -> filterMatchText(case.field("rule").jsonObject.toRule()) }
  }

  @Test
  fun `非法正则就地报错、合法的放行——文案只钉前缀`() {
    val bad = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "([", regex = true))
    assertNotNull(bad)
    assertTrue(bad.startsWith("正则表达式不合法："), bad)
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "^\\[水\\]", regex = true)))
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "([")))
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.USER, "([", regex = true)))
  }

  @Test
  fun `compileFilterRegex 写错了给 null,不抛`() {
    assertNull(compileFilterRegex("([未闭合"))
    assertNotNull(compileFilterRegex("^\\[水\\]"))
    val compiled = assertNotNull(compileFilterRegex("steam"))
    assertTrue(compiled.matcher("STEAM 夏促").find())
  }

  @Test
  fun `正则不带 g——同一条规则连判多次结果稳定`() {
    val rules = listOf(rule(FilterRuleKind.KEYWORD, "搬运", regex = true))
    repeat(3) {
      assertNotNull(matchFilterRules(rules, FilterSubject(title = "搬运工又来了")))
    }
  }

  @Test
  fun `P3-05 规则文本超长被挡在存之前`() {
    val long = "广".repeat(MAX_RULE_VALUE_LENGTH + 1)
    val message = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, long))
    assertEquals("关键词最长 $MAX_RULE_VALUE_LENGTH 个字符", message)
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "广".repeat(MAX_RULE_VALUE_LENGTH))))
  }

  @Test
  fun `P3-05 pattern 超长不编译,也就永不命中`() {
    val pattern = "a".repeat(MAX_REGEX_PATTERN_LENGTH + 1)
    assertNull(compileFilterRegex(pattern))
    val rules = listOf(rule(FilterRuleKind.KEYWORD, pattern, regex = true))
    assertNull(matchFilterRules(rules, FilterSubject(title = pattern)))
  }

  @Test
  fun `P3-05 嵌套量词被粗检挡下,不进正则引擎`() {
    assertNull(compileFilterRegex("(a+)+\$"))
    val message = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "(a+)+\$", regex = true))
    assertNotNull(message)
    assertTrue(message.startsWith("正则表达式不合法："), message)
    assertNotNull(compileFilterRegex("a\\s{2,}b"))
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "a\\s{2,}b", regex = true)))
  }

  @Test
  fun `P3-05 病态 pattern 碰上长正文既不卡死也不抛,按不命中处理`() {
    val rules = listOf(rule(FilterRuleKind.KEYWORD, "(?:a|aa)+b", regex = true))
    val subject = FilterSubject(title = "a".repeat(4000) + "c")
    val started = System.nanoTime()
    assertNull(matchFilterRules(rules, subject))
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue(elapsedMs < 5_000, "资源上限没生效,跑了 ${elapsedMs}ms")
  }

  @Test
  fun `P3-05 超长正文只给正则看前 MAX_REGEX_INPUT_LENGTH 个字符`() {
    val rules = listOf(rule(FilterRuleKind.KEYWORD, "针$", regex = true))
    assertNotNull(
      matchFilterRules(rules, FilterSubject(title = "干".repeat(MAX_REGEX_INPUT_LENGTH - 1) + "针")),
    )
    assertNull(
      matchFilterRules(rules, FilterSubject(title = "干".repeat(MAX_REGEX_INPUT_LENGTH) + "针")),
    )
    assertNotNull(
      matchFilterRules(
        listOf(rule(FilterRuleKind.KEYWORD, "针")),
        FilterSubject(title = "干".repeat(MAX_REGEX_INPUT_LENGTH) + "针"),
      ),
    )
  }

  private fun rule(kind: FilterRuleKind, value: String, regex: Boolean = false): FilterRule =
    createFilterRule(FilterRuleInput(kind, value, regex), nowSeconds = 100)
}

private fun FilterRule.toGoldenMap(): Map<String, Any?> = buildMap {
  createdAt?.let { put("createdAt", it) }
  put("id", id)
  put("kind", kind.wire)
  put("origin", origin.wire)
  put("regex", regex)
  uid?.let { put("uid", it) }
  put("value", value)
}

private fun JsonObject.toRule(): FilterRule = FilterRule(
  id = getValue("id").jsonPrimitive.content,
  kind = FilterRuleKind.of(getValue("kind").jsonPrimitive.content),
  origin = FilterRuleOrigin.of(getValue("origin").jsonPrimitive.content),
  value = getValue("value").jsonPrimitive.content,
  regex = get("regex")?.jsonPrimitive?.booleanOrNull == true,
  uid = get("uid")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
  createdAt = get("createdAt")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
)

private fun JsonObject.toRuleInput(): FilterRuleInput = FilterRuleInput(
  kind = FilterRuleKind.of(getValue("kind").jsonPrimitive.content),
  value = getValue("value").jsonPrimitive.content,
  regex = get("regex")?.jsonPrimitive?.booleanOrNull == true,
  uid = get("uid")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
)

private fun JsonObject.toSubject(): FilterSubject = FilterSubject(
  author = get("author")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
  authorId = get("authorId")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
  title = get("title")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
  content = get("content")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
)

private fun GoldenCase.ruleInput(): FilterRuleInput = inputObject().toRuleInput()

private fun GoldenCase.rules(): List<FilterRule> =
  field("rules").jsonArray.map { (it as JsonObject).toRule() }
