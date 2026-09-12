package com.chasel.ng2n.ui.filters

import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.FilterRuleOrigin
import com.chasel.ng2n.ui.board.dateText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 屏蔽规则行第二行灰字的文案 —— 与 RN 侧 `src/app/filters.tsx` 的 `localRuleSub`
 * **逐字对拍**(票 18 的验收要按文案点)。
 *
 * 时间那一半交给 [dateText](设备时区),这里只固定「有添加时间 / 没有添加时间」
 * 两种拼法与四种生效范围文案。
 */
class FiltersScreenTextTest {

  private fun rule(
    kind: FilterRuleKind,
    regex: Boolean = false,
    uid: Long? = null,
    createdAt: Long? = null,
  ) = FilterRule(
    id = "local:${kind.wire}:x",
    kind = kind,
    origin = FilterRuleOrigin.LOCAL,
    value = "x",
    regex = regex,
    uid = uid,
    createdAt = createdAt,
  )

  @Test
  fun `关键词规则分正则与子串两句话`() {
    assertEquals("命中标题或正文", localRuleSub(rule(FilterRuleKind.KEYWORD)))
    assertEquals("正则 · 命中标题或正文", localRuleSub(rule(FilterRuleKind.KEYWORD, regex = true)))
  }

  @Test
  fun `用户规则带了 uid 就报 uid 没带就报按用户名匹配`() {
    assertEquals("按用户名匹配", localRuleSub(rule(FilterRuleKind.USER)))
    assertEquals("uid 42", localRuleSub(rule(FilterRuleKind.USER, uid = 42)))
  }

  @Test
  fun `分类规则一律全部版块`() {
    assertEquals("全部版块", localRuleSub(rule(FilterRuleKind.CATEGORY)))
  }

  @Test
  fun `有添加时间就拼在最前面 官方规则没有添加时间就只剩生效范围`() {
    val at = 1_700_000_000L
    assertEquals(
      "${dateText(at)} 添加 · 命中标题或正文",
      localRuleSub(rule(FilterRuleKind.KEYWORD, createdAt = at)),
    )
    assertEquals("命中标题或正文", localRuleSub(rule(FilterRuleKind.KEYWORD)))
  }
}
