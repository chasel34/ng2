package com.chasel.ng2n.data.settings

import kotlinx.serialization.Serializable

enum class FilterRuleKind(val wire: String, val label: String) {
  USER("user", "用户"),
  KEYWORD("keyword", "关键词"),
  CATEGORY("category", "分类"),
  ;

  companion object {
    fun fromWire(value: String?): FilterRuleKind? = entries.firstOrNull { it.wire == value }
  }
}

enum class FilterRuleOrigin(val wire: String) {
  LOCAL("local"), OFFICIAL("official");

  companion object {
    fun fromWire(value: String?): FilterRuleOrigin? = entries.firstOrNull { it.wire == value }
  }
}

@Serializable
data class FilterRule(
  val id: String,
  val kind: String,
  val origin: String,
  val value: String,
  val regex: Boolean = false,
  val uid: Long? = null,
  /** Unix 秒时间戳；官方规则可能不提供。 */
  val createdAt: Long? = null,
) {
  val kindEnum: FilterRuleKind? get() = FilterRuleKind.fromWire(kind)
  val originEnum: FilterRuleOrigin? get() = FilterRuleOrigin.fromWire(origin)
}

data class FilterRuleInput(
  val kind: FilterRuleKind,
  val value: String,
  val regex: Boolean = false,
  val uid: Long? = null,
)

private val WHITESPACE = Regex("\\s+")

fun normalizeRuleValue(value: String, regex: Boolean = false): String {
  val trimmed = value.trim()
  return if (regex) trimmed else trimmed.replace(WHITESPACE, " ")
}

fun filterRuleId(origin: FilterRuleOrigin, kind: FilterRuleKind, value: String): String =
  "${origin.wire}:${kind.wire}:${value.lowercase()}"

fun validateFilterRule(input: FilterRuleInput): String? {
  val regex = input.regex && input.kind == FilterRuleKind.KEYWORD
  val value = normalizeRuleValue(input.value, regex)
  if (value == "") return "请输入要屏蔽的${input.kind.label}"
  if (!regex) return null

  return try {
    Regex(value, RegexOption.IGNORE_CASE)
    null
  } catch (cause: IllegalArgumentException) {
    "正则表达式不合法:${cause.message ?: "语法有误"}"
  }
}

fun createFilterRule(input: FilterRuleInput, nowSeconds: Long): FilterRule {
  val regex = input.regex && input.kind == FilterRuleKind.KEYWORD
  val value = normalizeRuleValue(input.value, regex)
  return FilterRule(
    id = filterRuleId(FilterRuleOrigin.LOCAL, input.kind, value),
    kind = input.kind.wire,
    origin = FilterRuleOrigin.LOCAL.wire,
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

fun sanitizeFilterRules(rules: List<FilterRule>): List<FilterRule> =
  rules.filter { it.id != "" && FilterRuleKind.fromWire(it.kind) != null }
