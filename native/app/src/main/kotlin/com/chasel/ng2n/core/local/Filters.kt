package com.chasel.ng2n.core.local

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 屏蔽规则的匹配器。直译 `src/core/local/filters.ts`,外加 **P3-05**(用户正则资源上限)。
 *
 * 三个来源的规则——本地的用户/关键词/分类、官方屏蔽词里的用户与关键词——
 * 进到这里统一成同一种 [FilterRule],主题列表与楼层流共用一次 [matchFilterRules]。
 * 这是刻意的:两处各写一套判定,迟早出现「列表藏了、点进去详情没折」的不一致。
 *
 * 两条贯穿全文的规矩:
 *
 * 1. **匹配一律大小写不敏感**。NGA 用户名与关键词都可能混大小写,用户加规则时
 *    不会想着还要区分。正则也统一带 `i`(JVM 侧还要额外带 `UNICODE_CASE`,
 *    否则非 ASCII 不折叠,与 JS 的 `i` 对不上)。
 * 2. **非法正则永不命中,也永不抛**。规则是用户手输的,一条写错的正则不能让
 *    整个列表白屏。
 *
 * ## P3-05:用户正则的资源上限(RN 版没有,移植时修)
 *
 * RN 版把用户手输的串直接 `new RegExp(pattern, 'i')` 就拿去 `test()`,没有任何
 * 长度或复杂度限制,也没有匹配预算:`(a+)+$` 这种嵌套量词碰上长正文就是指数级回溯,
 * 在 JS 上表现为整个 UI 线程卡死。这里加三道闸(见 [MAX_RULE_VALUE_LENGTH]、
 * [MAX_REGEX_PATTERN_LENGTH]、[MAX_REGEX_STEPS]),并把编译缓存改成有界 LRU。
 */

/** 规则的三类(设计稿屏蔽规则页「本地规则」tab 的三种行)。 */
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

/** 规则从哪来:本机存储,还是 NGA 账号云端的官方屏蔽词。 */
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
  /** `<origin>:<kind>:<归一化后的 value>`,同一条规则重复添加会覆盖而不是并存 */
  val id: String,
  val kind: FilterRuleKind,
  val origin: FilterRuleOrigin,
  /** 规则原文(用户名 / 关键词 / 分类名),也是列表里显示的那一行 */
  val value: String,
  /** 按正则解释 [value](只有本地关键词能开) */
  val regex: Boolean,
  /**
   * 用户规则可带 uid。带了就以 uid 为准:楼层作者改了名照样认得出,
   * 而官方屏蔽表本来就是 `uid/用户名` 成对存的。
   */
  val uid: Long? = null,
  /** 添加时间,秒级 unix。官方规则拿不到添加时间,缺省 */
  val createdAt: Long? = null,
)

/** 被判定的对象。主题行没有正文、楼层没有标题,缺的字段就是缺。 */
data class FilterSubject(
  /** 作者名(匿名已还原,见 [resolveAuthorName]) */
  val author: String? = null,
  /** 作者 uid,匿名楼层没有 */
  val authorId: Long? = null,
  /** 主题标题;分类标签从它里面解 */
  val title: String? = null,
  /** 楼层正文(BBCode 原文即可,关键词按原文匹配) */
  val content: String? = null,
)

/** 新增规则对话框收上来的东西。 */
data class FilterRuleInput(
  val kind: FilterRuleKind,
  val value: String,
  val regex: Boolean = false,
  val uid: Long? = null,
)

// --- P3-05 的三道闸 ---------------------------------------------------------

/** 规则文本长度上限。超过就不是「屏蔽词」而是往里灌数据了。 */
const val MAX_RULE_VALUE_LENGTH = 256

/** 正则 pattern 长度上限。 */
const val MAX_REGEX_PATTERN_LENGTH = 256

/**
 * 单次匹配的步数预算(以「读取输入字符的次数」计)。
 *
 * JVM 的 `java.util.regex` 没有原生超时,但 `Matcher` 读输入**只经过**
 * `CharSequence.charAt`——所以给它喂一条会计数的 `CharSequence`,超预算就抛,
 * 等价于一个确定性的步数上限(比墙钟超时更可复现:同一条规则 + 同一段正文
 * 在快机器和慢机器上是同一个结果)。命中预算按「不命中」处理。
 */
const val MAX_REGEX_STEPS = 200_000

/** 编译缓存的容量上限(RN 版那张 `Map` 是无界的,规则表被灌爆时它自己就是个泄漏)。 */
private const val REGEX_CACHE_CAPACITY = 64

/**
 * 嵌套量词的粗检:一个**带量词的分组**,其内部还有量词——`(a+)+`、`(\d*|x)+`、
 * `(a{1,9})*` 这一族正是灾难性回溯的经典形状。
 *
 * 只做粗检:漏判的由 [MAX_REGEX_STEPS] 兜底,不追求识别所有病态 pattern。
 */
private val NESTED_QUANTIFIER = Regex("""\((?:[^()\\]|\\.)*[*+}][^()]*\)\s*[*+{]""")

/** 各类规则在列表行与折叠行里的中文名,UI 两处共用,别各写各的。 */
val FILTER_KIND_LABELS: Map<FilterRuleKind, String> =
  FilterRuleKind.entries.associateWith { it.label }

/**
 * 归一化规则内容:去首尾空白、把内部连续空白压成一个空格。
 *
 * 压空白是为了让「已经加过了」判得准(`  张 三 ` 与 `张 三` 是同一条);
 * 正则不压——`\s{2,}` 这种写法里连续空白是有意义的。
 */
fun normalizeRuleValue(value: String, regex: Boolean = false): String {
  val trimmed = value.jsTrim()
  return if (regex) trimmed else JS_WHITESPACE_RUN.replace(trimmed, " ")
}

/** 规则 id。同来源、同类型、同内容即同一条规则。 */
fun filterRuleId(origin: FilterRuleOrigin, kind: FilterRuleKind, value: String): String =
  "${origin.wire}:${kind.wire}:${value.lowercase()}"

/** 编译过的正则缓存。规则表就十来条,列表每行都重编一次纯属浪费。**有界**(P3-05)。 */
private val regexCache = object : LinkedHashMap<String, Pattern?>(16, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern?>): Boolean =
    size > REGEX_CACHE_CAPACITY
}

/**
 * 把关键词编译成正则;写错了、或超出 P3-05 的资源上限,返回 `null` 而不是抛。
 *
 * `CASE_INSENSITIVE or UNICODE_CASE` 才等价于 JS 的 `i`;不加 `MULTILINE`——
 * TS 侧也没加 `m`,`^` 认的是整段 haystack 的开头(标题与正文是用 `\n` 拼起来的,
 * 加了 `m` 会让 `^关键词` 平白多命中正文的每一行开头)。
 * 也不加 `g`:带 `g` 的正则在 JS 侧 `test` 有 lastIndex 状态,复用同一个实例会隔行漏判;
 * JVM 侧每次 `matcher()` 都是新的,这条不成问题,但语义要对齐。
 */
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

/** 步数预算用尽。内部信号,不进日志、不带栈(建栈本身就有开销)。 */
private class RegexStepBudgetExceeded : RuntimeException(null, null, false, false)

/** 只经过 `charAt` 计数的输入包装——`Matcher` 读输入的唯一入口就是它。 */
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

/** 带步数预算的 `find()`。超预算 = 不命中(P3-05:一条病态规则不能拖垮整屏)。 */
private fun matchesWithinBudget(pattern: Pattern, text: String): Boolean = try {
  pattern.matcher(BudgetedCharSequence(text, intArrayOf(MAX_REGEX_STEPS))).find()
} catch (_: RegexStepBudgetExceeded) {
  false
}

/**
 * 校验一条待新增的规则,返回给对话框就地显示的错误文案;没问题返回 `null`。
 * 只有关键词能开正则——用户名与分类是精确比对,正则开关在 UI 上也不该出现。
 *
 * 长度上限与嵌套量词那两档是 **P3-05 新增**,RN 版没有(见文件头)。
 */
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

/** 造一条本地规则。调用前先过 [validateFilterRule]——这里不再校验。 */
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

/** 加一条规则:同 id 的旧规则被换掉,新规则排最前(设计稿列表新加的在上面)。 */
fun upsertFilterRule(rules: List<FilterRule>, rule: FilterRule): List<FilterRule> =
  listOf(rule) + rules.filter { it.id != rule.id }

/** 删一条规则。删不存在的 id 是 no-op(返回的还是原内容)。 */
fun removeFilterRule(rules: List<FilterRule>, id: String): List<FilterRule> =
  rules.filter { it.id != id }

/** NGA 的「标题标签」:半角方括号、里面不含括号的短串。 */
private val TITLE_TAG = Regex("""\[([^\[\]]{1,20})\]""")

/**
 * 从标题里取出方括号分类标签(如 `[讨论]转帖求证`)。
 *
 * 只认半角方括号、只认不含括号的短串:正文里出现的 `[b]` 这类 BBCode 也会被取到,
 * 但分类规则是精确比对,取多了不会误伤。
 */
fun topicCategories(title: String): List<String> = TITLE_TAG.findAll(title)
  .map { it.groupValues[1].jsTrim() }
  .filter { it.isNotEmpty() }
  .toList()

/**
 * 判一个主题行 / 一个楼层要不要被屏蔽,命中就返回**第一条**命中的规则
 * (调用方要拿它写「已屏蔽 xxx 的楼层」,所以返回规则本身而不是 boolean)。
 *
 * 规则表按调用方给的顺序看:本地规则在前、官方在后时,折叠行会优先报本地那条,
 * 用户点「解除」时也就落在他自己加的那条上。
 */
fun matchFilterRules(rules: List<FilterRule>, subject: FilterSubject): FilterRule? {
  if (rules.isEmpty()) return null

  val author = subject.author?.jsTrim()?.lowercase()
  // 标题与正文拼一起过一遍关键词:关键词规则本来就是「标题或正文命中即算」,
  // 拆成两次 includes 只是把同一件事说两遍
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
      // uid 优先:改名换头像都跑不掉,官方屏蔽表存的也正是 uid
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

/** 楼层折叠成一行灰字时的那句话(详情页用)。 */
fun filterMatchText(rule: FilterRule): String = when (rule.kind) {
  FilterRuleKind.USER -> "已屏蔽 ${rule.value} 的楼层"
  FilterRuleKind.KEYWORD -> "已屏蔽含「${rule.value}」的楼层"
  FilterRuleKind.CATEGORY -> "已屏蔽分类「${rule.value}」的楼层"
}
