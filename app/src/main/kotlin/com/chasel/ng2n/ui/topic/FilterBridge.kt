package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.local.FilterRule as MatchRule
import com.chasel.ng2n.core.local.FilterRuleKind as MatchKind
import com.chasel.ng2n.core.local.FilterRuleOrigin as MatchOrigin
import com.chasel.ng2n.data.settings.FilterRule as StoredRule
import com.chasel.ng2n.data.settings.FilterRuleKind as StoredKind
import com.chasel.ng2n.data.settings.FilterRuleOrigin as StoredOrigin

/**
 * 屏蔽规则的**两份模型**之间的搬运。
 *
 * 票 14 在 `data/settings/FilterRules.kt` 落了一份「怎么存」的模型(`kind`/`origin`
 * 是字符串,坏条目跳过);票 10 在 `core/local/Filters.kt` 落了一份「怎么判」的模型
 * (枚举 + P3-05 的四道闸)。两边字段一一对应,只是枚举与字符串的差别。
 *
 * **票外问题(已记 Comments)**:这两份本该合一 —— 存储那份的 `kind: String`
 * 就是为了容错落盘,而判定那份要枚举。合并归主控排期,本票只做搬运,不动任何一边。
 */
internal fun StoredRule.toMatchRule(): MatchRule? {
  val kind = StoredKind.fromWire(kind)?.toMatchKind() ?: return null
  val origin = StoredOrigin.fromWire(origin)?.toMatchOrigin() ?: MatchOrigin.LOCAL
  return MatchRule(
    id = id,
    kind = kind,
    origin = origin,
    value = value,
    regex = regex,
    uid = uid,
    createdAt = createdAt,
  )
}

/** 判定模型 → 存储模型(「屏蔽此人」新加的规则要落盘)。 */
internal fun MatchRule.toStoredRule(): StoredRule = StoredRule(
  id = id,
  kind = kind.wire,
  origin = origin.wire,
  value = value,
  regex = regex,
  uid = uid,
  createdAt = createdAt,
)

private fun StoredKind.toMatchKind(): MatchKind = when (this) {
  StoredKind.USER -> MatchKind.USER
  StoredKind.KEYWORD -> MatchKind.KEYWORD
  StoredKind.CATEGORY -> MatchKind.CATEGORY
}

private fun StoredOrigin.toMatchOrigin(): MatchOrigin = when (this) {
  StoredOrigin.LOCAL -> MatchOrigin.LOCAL
  StoredOrigin.OFFICIAL -> MatchOrigin.OFFICIAL
}
