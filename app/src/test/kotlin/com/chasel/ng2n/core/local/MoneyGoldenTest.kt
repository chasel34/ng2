package com.chasel.ng2n.core.local

import com.chasel.ng2n.golden.doubleField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `money` domain 全量对拍(24 条)。纯显示换算(API 文档 §11.1)。
 *
 * `splitMoney(NaN)` 没进金样本(README 规范 4:金样本里不允许非有限数字),
 * 那一档由下面的手写单测锁。
 */
class MoneyGoldenTest {

  @Test
  fun `money 金样本全量对拍`() = runGoldenDomain("money") {
    fn("splitMoney") { case -> splitMoney(case.doubleField("copperTotal")).toGoldenMap() }
    fn("formatMoney") { case -> formatMoney(case.doubleField("copperTotal")) }
    fn("toReputation") { case -> toReputation(case.doubleField("raw")) }
    fn("formatReputation") { case -> formatReputation(case.doubleField("reputation")) }
  }

  // --- 手工移植:`money.test.ts` 里进不了金样本的那一条 -------------------------

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
    // 直接对负数取模会拆出 -1/-23/-45 这种读不出来的东西
    assertEquals(Money(1, 23, 45, true), splitMoney(-12345.0))
  }
}

private fun Money.toGoldenMap(): Map<String, Any?> = mapOf(
  "copper" to copper,
  "gold" to gold,
  "negative" to negative,
  "silver" to silver,
)
