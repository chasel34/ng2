package com.chasel.ng2n.data.settings

import kotlinx.serialization.Serializable

/**
 * 本地屏蔽规则的存储模型 —— `src/core/local/filters.ts` 里**属于存储的那一半**
 * (规则表的形状、归一化、id、落盘容错)。
 *
 * 判定(`matchFilterRules`)与官方屏蔽词的云端整表覆盖归**票 17**(屏蔽设置屏),
 * 票 14 只负责「这张表怎么存、怎么读、坏条目怎么办」——RN 版对应的是
 * MMKV key `filters/local-rules`。
 *
 * 两条贯穿全文的规矩(照抄,判定层会用到):
 * 1. 匹配一律大小写不敏感 —— id 里 value 统一小写;
 * 2. 非法正则永不命中也永不抛 —— 存之前 [validateFilterRule] 就把话说清楚。
 */

/** 规则的三类(设计稿屏蔽规则页「本地规则」tab 的三种行)。 */
enum class FilterRuleKind(val wire: String, val label: String) {
  USER("user", "用户"),
  KEYWORD("keyword", "关键词"),
  CATEGORY("category", "分类"),
  ;

  companion object {
    fun fromWire(value: String?): FilterRuleKind? = entries.firstOrNull { it.wire == value }
  }
}

/** 规则从哪来:本机 DataStore,还是 NGA 账号云端的官方屏蔽词。 */
enum class FilterRuleOrigin(val wire: String) {
  LOCAL("local"), OFFICIAL("official");

  companion object {
    fun fromWire(value: String?): FilterRuleOrigin? = entries.firstOrNull { it.wire == value }
  }
}

@Serializable
data class FilterRule(
  /** `<origin>:<kind>:<归一化后的 value 小写>`,同一条规则重复添加会覆盖而不是并存 */
  val id: String,
  val kind: String,
  val origin: String,
  /** 规则原文(用户名 / 关键词 / 分类名),也是列表里显示的那一行 */
  val value: String,
  /** 按正则解释 [value](只有本地关键词能开) */
  val regex: Boolean = false,
  /**
   * 用户规则可带 uid。带了就以 uid 为准:楼层作者改了名照样认得出,
   * 而官方屏蔽表本来就是 `uid/用户名` 成对存的。
   */
  val uid: Long? = null,
  /** 添加时间,秒级 unix。官方规则拿不到添加时间,缺省 */
  val createdAt: Long? = null,
) {
  val kindEnum: FilterRuleKind? get() = FilterRuleKind.fromWire(kind)
  val originEnum: FilterRuleOrigin? get() = FilterRuleOrigin.fromWire(origin)
}

/** 新增规则对话框收上来的东西。 */
data class FilterRuleInput(
  val kind: FilterRuleKind,
  val value: String,
  val regex: Boolean = false,
  val uid: Long? = null,
)

private val WHITESPACE = Regex("\\s+")

/**
 * 归一化规则内容:去首尾空白、把内部连续空白压成一个空格。
 *
 * 压空白是为了让「已经加过了」判得准(`  张 三 ` 与 `张 三` 是同一条);
 * **正则不压** —— `\s{2,}` 这种写法里连续空白是有意义的。
 */
fun normalizeRuleValue(value: String, regex: Boolean = false): String {
  val trimmed = value.trim()
  return if (regex) trimmed else trimmed.replace(WHITESPACE, " ")
}

/** 规则 id。同来源、同类型、同内容即同一条规则。 */
fun filterRuleId(origin: FilterRuleOrigin, kind: FilterRuleKind, value: String): String =
  "${origin.wire}:${kind.wire}:${value.lowercase()}"

/**
 * 校验一条待新增的规则,返回给对话框就地显示的错误文案;没问题返回 null。
 * 只有关键词能开正则 —— 用户名与分类是精确比对,正则开关在 UI 上也不该出现。
 */
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

/** 造一条本地规则。调用前先过 [validateFilterRule] —— 这里不再校验。 */
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

/** 加一条规则:同 id 的旧规则被换掉,新规则排最前(设计稿列表新加的在上面)。 */
fun upsertFilterRule(rules: List<FilterRule>, rule: FilterRule): List<FilterRule> =
  listOf(rule) + rules.filter { it.id != rule.id }

/** 删一条规则。删不存在的 id 是 no-op。 */
fun removeFilterRule(rules: List<FilterRule>, id: String): List<FilterRule> =
  rules.filter { it.id != id }

/**
 * 落盘的规则表读回来。**坏条目跳过就好,别让一条脏数据把整张表清空**
 * (RN 版 `loadRules` 的原话)。
 */
fun sanitizeFilterRules(rules: List<FilterRule>): List<FilterRule> =
  rules.filter { it.id != "" && FilterRuleKind.fromWire(it.kind) != null }
