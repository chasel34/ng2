/**
 * 屏蔽规则的匹配器（CONTEXT.md「屏蔽规则」）。纯 TS：不碰存储、不发请求。
 *
 * 三个来源的规则——本地的用户/关键词/分类、官方屏蔽词里的用户与关键词——
 * 进到这里统一成同一种 `FilterRule`，主题列表与楼层流共用一次 `matchFilterRules`。
 * 这是刻意的：两处各写一套判定，迟早出现「列表藏了、点进去详情没折」的不一致。
 *
 * 两条贯穿全文的规矩：
 *
 * 1. **匹配一律大小写不敏感**。NGA 用户名与关键词都可能混大小写，
 *    用户加规则时不会想着还要区分。正则也统一带 `i`。
 * 2. **非法正则永不命中，也永不抛**。规则是用户手输的，一条写错的正则不能让
 *    整个列表白屏——`compileFilterRegex` 把编译失败折成 undefined，
 *    新增对话框另外调 `validateFilterRule` 在存之前就把话说清楚。
 */

/** 规则的三类（设计稿屏蔽规则页「本地规则」tab 的三种行）。 */
export type FilterRuleKind = 'user' | 'keyword' | 'category'

/** 规则从哪来：本机 MMKV，还是 NGA 账号云端的官方屏蔽词。 */
export type FilterRuleOrigin = 'local' | 'official'

/** 各类规则在列表行与折叠行里的中文名，UI 两处共用，别各写各的。 */
export const FILTER_KIND_LABELS: Readonly<Record<FilterRuleKind, string>> = {
  user: '用户',
  keyword: '关键词',
  category: '分类',
}

export interface FilterRule {
  /** `<origin>:<kind>:<归一化后的 value>`，同一条规则重复添加会覆盖而不是并存 */
  readonly id: string
  readonly kind: FilterRuleKind
  readonly origin: FilterRuleOrigin
  /** 规则原文（用户名 / 关键词 / 分类名），也是列表里显示的那一行 */
  readonly value: string
  /** 按正则解释 `value`（只有本地关键词能开） */
  readonly regex: boolean
  /**
   * 用户规则可带 uid。带了就以 uid 为准：楼层作者改了名照样认得出，
   * 而官方屏蔽表本来就是 `uid/用户名` 成对存的。
   */
  readonly uid?: number
  /** 添加时间，秒级 unix。官方规则拿不到添加时间，缺省 */
  readonly createdAt?: number
}

/** 被判定的对象。主题行没有正文、楼层没有标题，缺的字段就是缺。 */
export interface FilterSubject {
  /** 作者名（匿名已还原，CONTEXT.md「匿名还原」） */
  readonly author?: string
  /** 作者 uid，匿名楼层没有 */
  readonly authorId?: number
  /** 主题标题；分类标签从它里面解 */
  readonly title?: string
  /** 楼层正文（BBCode 原文即可，关键词按原文匹配） */
  readonly content?: string
}

/** 新增规则对话框收上来的东西。 */
export interface FilterRuleInput {
  readonly kind: FilterRuleKind
  readonly value: string
  readonly regex?: boolean
  readonly uid?: number
}

/**
 * 归一化规则内容：去首尾空白、把内部连续空白压成一个空格。
 *
 * 压空白是为了让「已经加过了」判得准（`  张 三 ` 与 `张 三` 是同一条）；
 * 正则不压——`\s{2,}` 这种写法里连续空白是有意义的。
 */
export function normalizeRuleValue(value: string, regex = false): string {
  const trimmed = value.trim()
  return regex ? trimmed : trimmed.replace(/\s+/g, ' ')
}

/** 规则 id。同来源、同类型、同内容即同一条规则。 */
export function filterRuleId(
  origin: FilterRuleOrigin,
  kind: FilterRuleKind,
  value: string,
): string {
  return `${origin}:${kind}:${value.toLowerCase()}`
}

/** 编译过的正则缓存。规则表就十来条，列表每行都重编一次纯属浪费。 */
const regexCache = new Map<string, RegExp | null>()

/**
 * 把关键词编译成正则；写错了返回 undefined 而不是抛。
 * `i` 是统一的大小写不敏感；不加 `g`——带 `g` 的正则 `test` 有 lastIndex 状态，
 * 复用同一个实例会隔行漏判。
 */
export function compileFilterRegex(pattern: string): RegExp | undefined {
  const cached = regexCache.get(pattern)
  if (cached !== undefined) return cached ?? undefined

  let compiled: RegExp | null
  try {
    compiled = new RegExp(pattern, 'i')
  } catch {
    compiled = null
  }
  regexCache.set(pattern, compiled)
  return compiled ?? undefined
}

/**
 * 校验一条待新增的规则，返回给对话框就地显示的错误文案；没问题返回 undefined。
 * 只有关键词能开正则——用户名与分类是精确比对，正则开关在 UI 上也不该出现。
 */
export function validateFilterRule(input: FilterRuleInput): string | undefined {
  const regex = input.regex === true && input.kind === 'keyword'
  const value = normalizeRuleValue(input.value, regex)
  if (value === '') return `请输入要屏蔽的${FILTER_KIND_LABELS[input.kind]}`
  if (!regex) return undefined

  try {
    new RegExp(value, 'i')
  } catch (cause) {
    return `正则表达式不合法：${cause instanceof Error ? cause.message : '语法有误'}`
  }
  return undefined
}

/** 造一条本地规则。调用前先过 `validateFilterRule`——这里不再校验。 */
export function createFilterRule(input: FilterRuleInput, nowSeconds: number): FilterRule {
  const regex = input.regex === true && input.kind === 'keyword'
  const value = normalizeRuleValue(input.value, regex)
  return {
    id: filterRuleId('local', input.kind, value),
    kind: input.kind,
    origin: 'local',
    value,
    regex,
    ...(input.uid === undefined ? {} : { uid: input.uid }),
    createdAt: nowSeconds,
  }
}

/** 加一条规则：同 id 的旧规则被换掉，新规则排最前（设计稿列表新加的在上面）。 */
export function upsertFilterRule(
  rules: readonly FilterRule[],
  rule: FilterRule,
): readonly FilterRule[] {
  return [rule, ...rules.filter((item) => item.id !== rule.id)]
}

/** 删一条规则。删不存在的 id 是 no-op（返回的还是原数组内容）。 */
export function removeFilterRule(
  rules: readonly FilterRule[],
  id: string,
): readonly FilterRule[] {
  return rules.filter((rule) => rule.id !== id)
}

/**
 * 从标题里取出方括号分类标签（NGA 的「标题标签」，如 `[讨论]转帖求证`）。
 *
 * 只认半角方括号、只认不含括号的短串：正文里出现的 `[b]` 这类 BBCode 也会被取到，
 * 但分类规则是精确比对，取多了不会误伤。
 */
export function topicCategories(title: string): readonly string[] {
  const tags: string[] = []
  for (const match of title.matchAll(/\[([^[\]]{1,20})\]/g)) {
    const tag = match[1]?.trim()
    if (tag !== undefined && tag !== '') tags.push(tag)
  }
  return tags
}

/**
 * 判一个主题行 / 一个楼层要不要被屏蔽，命中就返回**第一条**命中的规则
 * （调用方要拿它写「已屏蔽 xxx 的楼层」，所以返回规则本身而不是 boolean）。
 *
 * 规则表按调用方给的顺序看：本地规则在前、官方在后时，折叠行会优先报本地那条，
 * 用户点「解除」时也就落在他自己加的那条上。
 */
export function matchFilterRules(
  rules: readonly FilterRule[],
  subject: FilterSubject,
): FilterRule | undefined {
  if (rules.length === 0) return undefined

  const author = subject.author?.trim().toLowerCase()
  // 标题与正文拼一起过一遍关键词：关键词规则本来就是「标题或正文命中即算」，
  // 拆成两次 includes 只是把同一件事说两遍
  const haystack = [subject.title, subject.content].filter((part) => part).join('\n')
  const folded = haystack.toLowerCase()
  const categories =
    subject.title === undefined
      ? []
      : topicCategories(subject.title).map((tag) => tag.toLowerCase())

  for (const rule of rules) {
    const value = rule.value.trim().toLowerCase()
    if (value === '') continue

    if (rule.kind === 'user') {
      // uid 优先：改名换头像都跑不掉，官方屏蔽表存的也正是 uid
      if (rule.uid !== undefined && subject.authorId === rule.uid) return rule
      if (author !== undefined && author === value) return rule
      continue
    }

    if (rule.kind === 'keyword') {
      if (haystack === '') continue
      if (rule.regex) {
        if (compileFilterRegex(rule.value)?.test(haystack) === true) return rule
        continue
      }
      if (folded.includes(value)) return rule
      continue
    }

    if (categories.includes(value)) return rule
  }
  return undefined
}

/** 楼层折叠成一行灰字时的那句话（详情页用）。 */
export function filterMatchText(rule: FilterRule): string {
  if (rule.kind === 'user') return `已屏蔽 ${rule.value} 的楼层`
  if (rule.kind === 'keyword') return `已屏蔽含「${rule.value}」的楼层`
  return `已屏蔽分类「${rule.value}」的楼层`
}
