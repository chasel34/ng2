package com.chasel.ng2n.core.local

import java.math.BigDecimal
import java.math.RoundingMode

const val REPUTATION_SCALE = 10.0

private const val COPPER_PER_SILVER = 100L
private const val COPPER_PER_GOLD = 10000L

fun toReputation(raw: Double): Double = raw / REPUTATION_SCALE

fun formatReputation(reputation: Double): String {
  if (!reputation.isFinite()) return reputation.toString()
  return BigDecimal(reputation).setScale(1, RoundingMode.HALF_UP).toPlainString()
}

data class Money(
  val gold: Long,
  val silver: Long,
  val copper: Long,
  val negative: Boolean,
)

fun splitMoney(copperTotal: Double): Money {
  val total = if (copperTotal.isFinite()) copperTotal.toLong() else 0L
  val magnitude = kotlin.math.abs(total)
  return Money(
    gold = magnitude / COPPER_PER_GOLD,
    silver = (magnitude % COPPER_PER_GOLD) / COPPER_PER_SILVER,
    copper = magnitude % COPPER_PER_SILVER,
    negative = total < 0L,
  )
}

fun formatMoney(copperTotal: Double): String {
  val (gold, silver, copper, negative) = splitMoney(copperTotal)
  return "${if (negative) "-" else ""}$gold.$silver.$copper"
}
