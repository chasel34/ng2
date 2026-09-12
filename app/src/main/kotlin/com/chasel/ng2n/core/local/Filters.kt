package com.chasel.ng2n.core.local

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class FilterRuleKind(val wire: String, val label: String) {
  USER("user", "用户"),
  KEYWORD("keyword", "关键词"),
  CATEGORY("category", "分类"),
  ;

  companion object {
    fun of(wire: String): FilterRuleKind = entries.firstOrNull { it.wire == wire }
      ?: error("认不出的屏蔽规则类型:$wire")
  }
}

enum class FilterRuleOrigin(val wire: String) {
  LOCAL("local"),
  OFFICIAL("official"),
  ;

  companion object {
    fun of(wire: String): FilterRuleOrigin = entries.firstOrNull { it.wire == wire }
      ?: error("认不出的屏蔽规则来源:$wire")
  }
}

data class FilterRule(
  val id: String,
  val kind: FilterRuleKind,
  val origin: FilterRuleOrigin,
  val value: String,
  val regex: Boolean,
  val uid: Long? = null,
  /** Unix 秒时间戳；官方规则可能不提供。 */
  val createdAt: Long? = null,
)

data class FilterSubject(
  val author: String? = null,
  val authorId: Long? = null,
  val title: String? = null,
  val content: String? = null,
)

data class FilterRuleInput(
  val kind: FilterRuleKind,
  val value: String,
  val regex: Boolean = false,
  val uid: Long? = null,
)

const val MAX_RULE_VALUE_LENGTH = 256

const val MAX_REGEX_PATTERN_LENGTH = 256

const val MAX_REGEX_STEPS = 200_000

const val MAX_REGEX_INPUT_LENGTH = 20_000

private const val REGEX_CACHE_CAPACITY = 64

private val NESTED_QUANTIFIER = Regex("""\((?:[^()\\]|\\.)*[*+}][^()]*\)\s*[*+{]""")

val FILTER_KIND_LABELS: Map<FilterRuleKind, String> =
  FilterRuleKind.entries.associateWith { it.label }

fun normalizeRuleValue(value: String, regex: Boolean = false): String {
  val trimmed = value.jsTrim()
  return if (regex) trimmed else JS_WHITESPACE_RUN.replace(trimmed, " ")
}

fun filterRuleId(origin: FilterRuleOrigin, kind: FilterRuleKind, value: String): String =
  "${origin.wire}:${kind.wire}:${value.lowercase()}"

private val regexCache = object : LinkedHashMap<String, Pattern?>(16, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern?>): Boolean =
    size > REGEX_CACHE_CAPACITY
}

fun compileFilterRegex(pattern: String): Pattern? {
  if (pattern.length > MAX_REGEX_PATTERN_LENGTH) return null
  if (NESTED_QUANTIFIER.containsMatchIn(pattern)) return null

  synchronized(regexCache) {
    if (regexCache.containsKey(pattern)) return regexCache[pattern]
  }
  val compiled = try {
    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
  } catch (_: PatternSyntaxException) {
    null
  }
  synchronized(regexCache) { regexCache[pattern] = compiled }
  return compiled
}

private class RegexStepBudgetExceeded : RuntimeException(null, null, false, false)

private class BudgetedCharSequence(
  private val delegate: CharSequence,
  private val remaining: IntArray,
) : CharSequence {
  override val length: Int get() = delegate.length

  override fun get(index: Int): Char {
    if (--remaining[0] < 0) throw RegexStepBudgetExceeded()
    return delegate[index]
  }

  override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
    BudgetedCharSequence(delegate.subSequence(startIndex, endIndex), remaining)

  override fun toString(): String = delegate.toString()
}

private fun matchesWithinBudget(pattern: Pattern, text: String): Boolean {
  val bounded = if (text.length > MAX_REGEX_INPUT_LENGTH) text.take(MAX_REGEX_INPUT_LENGTH) else text
  return try {
    pattern.matcher(BudgetedCharSequence(bounded, intArrayOf(MAX_REGEX_STEPS))).find()
  } catch (_: RegexStepBudgetExceeded) {
    false
  } catch (_: StackOverflowError) {
    false
  }
}

fun validateFilterRule(input: FilterRuleInput): String? {
  val regex = input.regex && input.kind == FilterRuleKind.KEYWORD
  val value = normalizeRuleValue(input.value, regex)
  if (value.isEmpty()) return "请输入要屏蔽的${input.kind.label}"
  if (value.length > MAX_RULE_VALUE_LENGTH) {
    return "${input.kind.label}最长 $MAX_RULE_VALUE_LENGTH 个字符"
  }
  if (!regex) return null

  if (NESTED_QUANTIFIER.containsMatchIn(value)) {
    return "正则表达式不合法：嵌套量词(如 `(a+)+`)会让匹配退化成指数级回溯,请改写"
  }
  return try {
    Pattern.compile(value, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    null
  } catch (cause: PatternSyntaxException) {
    "正则表达式不合法：${cause.description ?: "语法有误"}"
  }
}

fun createFilterRule(input: FilterRuleInput, nowSeconds: Long): FilterRule {
  val regex = input.regex && input.kind == FilterRuleKind.KEYWORD
  val value = normalizeRuleValue(input.value, regex)
  return FilterRule(
    id = filterRuleId(FilterRuleOrigin.LOCAL, input.kind, value),
    kind = input.kind,
    origin = FilterRuleOrigin.LOCAL,
    value = value,
    regex = regex,
    uid = input.uid,
    createdAt = nowSeconds,
  )
}

fun upsertFilterRule(rules: List<FilterRule>, rule: FilterRule): List<FilterRule> =
  listOf(rule) + rules.filter { it.id != rule.id }

fun removeFilterRule(rules: List<FilterRule>, id: String): List<FilterRule> =
  rules.filter { it.id != id }

private val TITLE_TAG = Regex("""\[([^\[\]]{1,20})\]""")

fun topicCategories(title: String): List<String> = TITLE_TAG.findAll(title)
  .map { it.groupValues[1].jsTrim() }
  .filter { it.isNotEmpty() }
  .toList()

fun matchFilterRules(rules: List<FilterRule>, subject: FilterSubject): FilterRule? {
  if (rules.isEmpty()) return null

  val author = subject.author?.jsTrim()?.lowercase()
  val haystack = listOfNotNull(subject.title, subject.content)
    .filter { it.isNotEmpty() }
    .joinToString("\n")
  val folded = haystack.lowercase()
  val categories = subject.title?.let { title -> topicCategories(title).map { it.lowercase() } }
    ?: emptyList()

  for (rule in rules) {
    val value = rule.value.jsTrim().lowercase()
    if (value.isEmpty()) continue

    if (rule.kind == FilterRuleKind.USER) {
      if (rule.uid != null && subject.authorId == rule.uid) return rule
      if (author != null && author == value) return rule
      continue
    }

    if (rule.kind == FilterRuleKind.KEYWORD) {
      if (haystack.isEmpty()) continue
      if (rule.regex) {
        val compiled = compileFilterRegex(rule.value)
        if (compiled != null && matchesWithinBudget(compiled, haystack)) return rule
        continue
      }
      if (folded.contains(value)) return rule
      continue
    }

    if (categories.contains(value)) return rule
  }
  return null
}

fun filterMatchText(rule: FilterRule): String = when (rule.kind) {
  FilterRuleKind.USER -> "已屏蔽 ${rule.value} 的楼层"
  FilterRuleKind.KEYWORD -> "已屏蔽含「${rule.value}」的楼层"
  FilterRuleKind.CATEGORY -> "已屏蔽分类「${rule.value}」的楼层"
}
