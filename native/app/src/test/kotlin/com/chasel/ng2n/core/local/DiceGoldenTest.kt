package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `dice` domain 全量对拍(23 条)。
 *
 * 金样本的 `input.text` 是楼层正文原文,README 要求「先 `parseBBCode(text)` 再
 * `resolveDice(ast, seed)`」。票 09 的解析器还没合并,所以这里先过 [parseMiniBBCode]
 * (只认 `[dice]`/`[collapse]` 等六种标签的临时抽取器)再 [diceScopeOf] 抽成
 * [DiceScope] 喂进去——**TODO(票 11/13):换成正式 `parseBBCode`。**
 */
class DiceGoldenTest {

  @Test
  fun `dice 金样本全量对拍`() = runGoldenDomain("dice") {
    fn("resolveDice") { case -> outcomesJson(case.text(), case.seed()) }
    fn("formatDiceTerms") { case -> formatDiceTerms(case.terms()) }
  }

  // --- 手工移植:`dice.test.ts` 里不落在金样本里的关系型断言 ---------------------

  @Test
  fun `一个楼层里的多颗骰子共用一条数列,不各自从头开始`() {
    val shared = rolls("[dice]d6[/dice][dice]d6[/dice][dice]d6[/dice]")
    val alone = rolls("[dice]d6[/dice]")
    assertEquals(alone[0], shared[0])
    assertNotEquals(listOf(alone[0], alone[0]), shared.drop(1))
  }

  @Test
  fun `折叠块里的骰子换一条数列——外层投过就接着外层的种子走`() {
    // 官方帮助原话:「将[dice]代码移入或移出折叠块……随机数结果会发生改变」
    val inside = rolls("[collapse=提要][dice]d100[/dice][/collapse]")
    val outside = rolls("[dice]d100[/dice]")
    assertNotEquals(outside, inside)
    // 外层先投一颗时,折叠块从外层推进后的种子接着走
    val second = rolls("[dice]d100[/dice][collapse][dice]d100[/dice][/collapse]")[1]
    assertEquals(rolls("[dice]d100[/dice][dice]d100[/dice]")[1], second)
  }

  @Test
  fun `引用别人的骰子代码会得到另一组点数(种子含本楼 pid)`() {
    val source = "[dice]d6[/dice]"
    val original = rolls(source, DiceSeed(60423359, 46162468, 857425480))
    val quoted = rolls(source, DiceSeed(66807492, 46162468, 857425600))
    assertEquals(listOf(3L), original)
    assertNotEquals(original, quoted)
  }

  @Test
  fun `同一楼层里两个写法相同的骰子各有各的点数`() {
    val outcomes = resolveDice(diceScopeOf(parseMiniBBCode("[dice]d100[/dice][dice]d100[/dice]")), SEED)
    assertEquals(2, outcomes.size)
    assertEquals(listOf(60L, 90L), outcomes.map { it.sum })
  }

  @Test
  fun `折叠块作用域的展开顺序与结果顺序一致`() {
    // 票 11/13 靠这条把结果贴回 AST 节点:flatten() 与 resolveDice() 必须同序
    val scope = diceScopeOf(
      parseMiniBBCode("[dice]2d6[/dice][collapse][dice]d100[/dice][/collapse][dice]d8[/dice]"),
    )
    assertEquals(listOf("2d6", "d8", "d100"), scope.flatten())
    assertEquals(scope.flatten(), resolveDice(scope, SEED).map { it.expression })
  }

  private companion object {
    val SEED = DiceSeed(authorId = 41417929, tid = 45150945, pid = 800000000)

    /** 一串骰子的点数,按文档顺序(TS 测试里的 `values()`)。 */
    fun rolls(source: String, seed: DiceSeed = SEED): List<Long> =
      resolveDice(diceScopeOf(parseMiniBBCode(source)), seed)
        .flatMap { outcome -> outcome.terms.filterIsInstance<DiceTerm.Roll>().map { it.value } }
  }
}

private fun GoldenCase.text(): String = stringField("text")

private fun GoldenCase.seed(): DiceSeed =
  DiceSeed(longField("authorId"), longField("tid"), longField("pid"))

private fun outcomesJson(text: String, seed: DiceSeed): JsonElement {
  val outcomes = resolveDice(diceScopeOf(parseMiniBBCode(text)), seed)
  return JsonArray(
    outcomes.map { outcome ->
      buildJsonObject {
        put("expression", outcome.expression)
        put("terms", JsonArray(outcome.terms.map { it.toJson() }))
        // OUT OF LIMIT 时 TS 侧整个 `sum` 键都不写(README 规范 2)
        outcome.sum?.let { put("sum", it) }
      }
    },
  )
}

private fun DiceTerm.toJson(): JsonElement = when (this) {
  is DiceTerm.Roll -> buildJsonObject {
    put("faces", faces)
    put("kind", "roll")
    put("value", value)
  }

  is DiceTerm.Constant -> buildJsonObject {
    put("kind", "constant")
    put("value", value)
  }
}

private fun GoldenCase.terms(): List<DiceTerm> = field("terms").jsonArray.map { element ->
  val term = element.jsonObject
  when (val kind = term.getValue("kind").jsonPrimitive.content) {
    "roll" -> DiceTerm.Roll(
      faces = term.getValue("faces").jsonPrimitive.long,
      value = term.getValue("value").jsonPrimitive.long,
    )

    "constant" -> DiceTerm.Constant(term.getValue("value").jsonPrimitive.long)
    else -> error("认不出的 DiceTerm.kind:$kind")
  }
}
