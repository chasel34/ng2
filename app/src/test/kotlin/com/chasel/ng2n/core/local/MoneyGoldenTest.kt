package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.doubleField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyGoldenTest {

  @Test
  fun `money 金样本全量对拍`() = runGoldenDomain("money") {
    fn("splitMoney") { case -> splitMoney(case.doubleField("copperTotal")).toGoldenMap() }
    fn("formatMoney") { case -> formatMoney(case.doubleField("copperTotal")) }
    fn("toReputation") { case -> toReputation(case.doubleField("raw")) }
    fn("formatReputation") { case -> formatReputation(case.doubleField("reputation")) }
  }

  @Test
  fun `非有限值先规整成 0 铜币`() {
    val zero = Money(gold = 0, silver = 0, copper = 0, negative = false)
    assertEquals(zero, splitMoney(Double.NaN))
    assertEquals(zero, splitMoney(Double.POSITIVE_INFINITY))
    assertEquals(zero, splitMoney(Double.NEGATIVE_INFINITY))
    assertEquals("0.0.0", formatMoney(Double.NaN))
  }

  @Test
  fun `小数先截断成整数铜币,负数按绝对值拆再标符号`() {
    assertEquals(Money(0, 1, 50, false), splitMoney(150.9))
    assertEquals(Money(1, 23, 45, true), splitMoney(-12345.0))
  }
}

private fun Money.toGoldenMap(): Map<String, Any?> = mapOf(
  "copper" to copper,
  "gold" to gold,
  "negative" to negative,
  "silver" to silver,
)
