package com.chasel.ng2n.core.bbcode

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `bbcode` domain 全量对拍(126 条,票 09 的验收项 1、2)。
 *
 * `input` 是楼层 `content` 原文,`expected` 是 29 种节点的 AST。Kotlin 侧把 AST 用
 * [encodeBBCode] 序列化成 `JsonElement` 再逐字段深比——**序列化形态本身也在对拍范围内**
 * (类鉴别器 `type` 的取值、可选字段的「缺席 vs null」),因为同一份 JSON 之后要进
 * Room 帖子缓存(票 13/14)。
 *
 * 特别注意的几条(见 `goldens/README.md`「bbcode」一节):
 * - `deep-nesting-5000`:5000 层 `[b]`,`MAX_NESTING_DEPTH = 64`,超出的开标签退化成文本;
 * - `unclosed-code-does-not-swallow`:raw 标签不许越过外层闭标签;
 * - `randomblock-not-supported`:不支持的标签原样透传,不凭空多出节点类型。
 */
class BBCodeGoldenTest {

  @Test
  fun `bbcode 金样本全量对拍`() = runGoldenDomain("bbcode") {
    fn("parseBBCode") { case -> encodeBBCode(parseBBCode(case.inputString())) }
  }
}
