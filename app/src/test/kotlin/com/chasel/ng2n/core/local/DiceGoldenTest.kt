package com.chasel.ng2n.core.local

import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.golden.GoldenCase
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import com.chasel.ng2n.ui.bbcode.diceScopeOf
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

class DiceGoldenTest {

  @Test
  fun `dice 金样本全量对拍`() = runGoldenDomain("dice") {
    fn("resolveDice") { case -> outcomesJson(case.text(), case.seed()) }
    fn("formatDiceTerms") { case -> formatDiceTerms(case.terms()) }
  }

  @Test
  fun `一个楼层里的多颗骰子共用一条数列,不各自从头开始`() {
    val shared = rolls("[dice]d6[/dice][dice]d6[/dice][dice]d6[/dice]")
    val alone = rolls("[dice]d6[/dice]")
    assertEquals(alone[0], shared[0])
    assertNotEquals(listOf(alone[0], alone[0]), shared.drop(1))
  }

  @Test
  fun `折叠块里的骰子换一条数列——外层投过就接着外层的种子走`() {
    val inside = rolls("[collapse=提要][dice]d100[/dice][/collapse]")
    val outside = rolls("[dice]d100[/dice]")
    assertNotEquals(outside, inside)
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
    val outcomes = resolveDice(diceScopeOf(parseBBCode("[dice]d100[/dice][dice]d100[/dice]")), SEED)
    assertEquals(2, outcomes.size)
    assertEquals(listOf(60L, 90L), outcomes.map { it.sum })
  }

  @Test
  fun `折叠块作用域的展开顺序与结果顺序一致`() {
    val scope = diceScopeOf(
      parseBBCode("[dice]2d6[/dice][collapse][dice]d100[/dice][/collapse][dice]d8[/dice]"),
    )
    assertEquals(listOf("2d6", "d8", "d100"), scope.flatten())
    assertEquals(scope.flatten(), resolveDice(scope, SEED).map { it.expression })
  }

  private companion object {
    val SEED = DiceSeed(authorId = 41417929, tid = 45150945, pid = 800000000)

    fun rolls(source: String, seed: DiceSeed = SEED): List<Long> =
      resolveDice(diceScopeOf(parseBBCode(source)), seed)
        .flatMap { outcome -> outcome.terms.filterIsInstance<DiceTerm.Roll>().map { it.value } }
  }
}

private fun GoldenCase.text(): String = stringField("text")

private fun GoldenCase.seed(): DiceSeed =
  DiceSeed(longField("authorId"), longField("tid"), longField("pid"))

private fun outcomesJson(text: String, seed: DiceSeed): JsonElement {
  val outcomes = resolveDice(diceScopeOf(parseBBCode(text)), seed)
  return JsonArray(
    outcomes.map { outcome ->
      buildJsonObject {
        put("expression", outcome.expression)
        put("terms", JsonArray(outcome.terms.map { it.toJson() }))
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
