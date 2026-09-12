package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `vote` domain 全量对拍(24 条)。
 *
 * `Floor.vote` 不是 BBCode,是 `~` 分隔的 kv 串;这里只解析到**只读渲染**够用为止
 * (spec §1 把投票操作排除在 v1 之外)。
 */
class VoteGoldenTest {

  @Test
  fun `vote 金样本全量对拍`() = runGoldenDomain("vote") {
    fn("parseVote") { case ->
      parseVote(case.stringField("raw"), case.longField("tid"))?.toGoldenMap()
    }
    fn("isVoteClosed") { case ->
      val vote = checkNotNull(parseVote(case.stringField("raw"), case.longField("tid")))
      isVoteClosed(vote, case.longField("now"))
    }
    fn("voteSharePercent") { case ->
      voteSharePercent(case.longField("votes"), case.longField("total"))
    }
  }

  // --- 手工移植:`vote.test.ts` 里金样本没单列的边界 ----------------------------

  @Test
  fun `空串、切不出一对、只有配置项时都没有投票`() {
    assertNull(parseVote("", 47331456))
    assertNull(parseVote("0", 47331456))
    assertNull(parseVote("max_select~1~end~0", 47331456))
  }

  @Test
  fun `末尾落单的一段直接丢掉,前面解出来的选项照常用`() {
    val vote = checkNotNull(parseVote("1~甲~_1~3,0,3~max_select", 47331456))
    assertEquals(1, vote.groups.single().options.size)
    assertEquals(1L, vote.maxSelect)
  }

  @Test
  fun `分组语法只对新帖生效`() {
    val raw = "1~甲~2~===第二组===~_1~5,0,9~_2~3,0,0"
    // 老帖(tid 不大于 38056407)的 `===` 是普通选项
    assertEquals(1, checkNotNull(parseVote(raw, 38056407)).groups.size)
    assertEquals(2, checkNotNull(parseVote(raw, 38056408)).groups.size)
  }
}

private fun Vote.toGoldenMap(): Map<String, Any?> = buildMap {
  endAt?.let { put("endAt", it) }
  put("groups", groups.map { it.toGoldenMap() })
  put("kind", kind.wire())
  put("maxSelect", maxSelect)
  put("multiple", multiple)
  requirement?.let { put("requirement", it) }
  put("resultAfterEnd", resultAfterEnd)
  put("resultAfterVote", resultAfterVote)
  scoreRange?.let { put("scoreRange", mapOf("max" to it.max, "min" to it.min)) }
  put("totalVotes", totalVotes)
  put("voters", voters)
}

private fun VoteGroup.toGoldenMap(): Map<String, Any?> = buildMap {
  put("options", options.map { it.toGoldenMap() })
  title?.let { put("title", it) }
  put("votes", votes)
}

private fun VoteOption.toGoldenMap(): Map<String, Any?> = mapOf(
  "chosen" to chosen,
  "id" to id,
  "points" to points,
  "title" to title,
  "votes" to votes,
)

/** 枚举名 → TS 侧那几个字面量。 */
private fun VoteKind.wire(): String = when (this) {
  VoteKind.VOTE -> "vote"
  VoteKind.BET -> "bet"
  VoteKind.SCORE -> "score"
  VoteKind.SCORE_ENTRY -> "scoreEntry"
  VoteKind.QA -> "qa"
}
