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

/**
 * `filters` domain 全量对拍(49 条)。
 *
 * 两条铁律锁在金样本里:**匹配一律大小写不敏感**、**非法正则永不命中也永不抛**。
 *
 * 金样本明确排除了两档(README「不在金样本里的东西」):
 * - `validateFilterRule` 的非法正则分支——文案里嵌着 JS 引擎的 `SyntaxError.message`,
 *   JVM 的措辞是另一套,拿它对拍等于把引擎实现钉死;这里只保证前缀是「正则表达式不合法：」;
 * - `compileFilterRegex`——返回的是编译产物,不是 JSON 值。
 *
 * 两档都由下面的手写单测锁,连同 **P3-05**(用户正则资源上限)新加的三道闸。
 */
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

  // --- 手工移植:金样本明确排除的两档 ------------------------------------------

  @Test
  fun `非法正则就地报错、合法的放行——文案只钉前缀`() {
    val bad = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "([", regex = true))
    assertNotNull(bad)
    assertTrue(bad.startsWith("正则表达式不合法："), bad)
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "^\\[水\\]", regex = true)))
    // 同一个串不开正则时只是普通关键词,不该被正则语法拦下
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "([")))
    // 正则开关只对关键词生效
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.USER, "([", regex = true)))
  }

  @Test
  fun `compileFilterRegex 写错了给 null,不抛`() {
    assertNull(compileFilterRegex("([未闭合"))
    assertNotNull(compileFilterRegex("^\\[水\\]"))
    // 大小写不敏感,且非 ASCII 也折叠(JS 的 `i` 是这个语义)
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

  // --- P3-05:用户正则的资源上限(RN 版没有,移植时修) --------------------------

  @Test
  fun `P3-05 规则文本超长被挡在存之前`() {
    val long = "广".repeat(MAX_RULE_VALUE_LENGTH + 1)
    val message = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, long))
    assertEquals("关键词最长 $MAX_RULE_VALUE_LENGTH 个字符", message)
    // 边界内照常放行
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
    // `(a+)+$` 碰上一长串 a 就是指数级回溯;RN 版会把 UI 线程卡死
    assertNull(compileFilterRegex("(a+)+\$"))
    val message = validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "(a+)+\$", regex = true))
    assertNotNull(message)
    assertTrue(message.startsWith("正则表达式不合法："), message)
    // 没有分组的量词是正常写法,不能误伤
    assertNotNull(compileFilterRegex("a\\s{2,}b"))
    assertNull(validateFilterRule(FilterRuleInput(FilterRuleKind.KEYWORD, "a\\s{2,}b", regex = true)))
  }

  @Test
  fun `P3-05 病态 pattern 碰上长正文既不卡死也不抛,按不命中处理`() {
    // `(?:a|aa)+b` 躲过了嵌套量词粗检(量词在分组外、组内没有量词),
    // 交给步数预算 + 输入长度上限兜底:这条在 RN 版会把 UI 线程跑到天荒地老,
    // 在 JVM 上则是先炸 StackOverflowError(java.util.regex 的 Loop 是递归的)。
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
    // 「针」落在上限之内 → 命中
    assertNotNull(
      matchFilterRules(rules, FilterSubject(title = "干".repeat(MAX_REGEX_INPUT_LENGTH - 1) + "针")),
    )
    // 落在上限之外 → 看不到(这是 P3-05 对 RN 版的有意偏离,普通子串规则不受影响)
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

// ---------------------------------------------------------------------------
// 金样本 JSON ↔ 领域类型
// ---------------------------------------------------------------------------

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

/** `validateFilterRule` 的 `input` 整体就是一个 `FilterRuleInput`。 */
private fun GoldenCase.ruleInput(): FilterRuleInput = inputObject().toRuleInput()

private fun GoldenCase.rules(): List<FilterRule> =
  field("rules").jsonArray.map { (it as JsonObject).toRule() }
