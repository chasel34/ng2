package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.local.FilterRule as MatchRule
import com.chasel.ng2n.core.local.FilterRuleKind as MatchKind
import com.chasel.ng2n.core.local.FilterRuleOrigin as MatchOrigin
import com.chasel.ng2n.data.settings.FilterRule as StoredRule
import com.chasel.ng2n.data.settings.FilterRuleKind as StoredKind
import com.chasel.ng2n.data.settings.FilterRuleOrigin as StoredOrigin

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
