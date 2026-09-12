package com.chasel.ng2n.core.local

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金钱与威望的显示规则(API 文档 §11.1)。直译 `src/core/local/money.ts`。
 *
 * 两条都是**纯显示换算**:服务端给的是一个整数,用户看到的是另一套单位。
 * 放 core/local 而不是各页面里各写一遍——楼层卡与资料页显示的必须是同一个数。
 */

/** 威望显示值 = 服务端 `rvrc`/`fame` ÷ 10。 */
const val REPUTATION_SCALE = 10.0

/** 1 银币 = 100 铜币,1 金币 = 100 银币(API 文档 §11.1:÷10000=金,余÷100=银,余=铜)。 */
private const val COPPER_PER_SILVER = 100L
private const val COPPER_PER_GOLD = 10000L

/** 服务端的 `rvrc`/`fame` → 显示用威望。可能是负数(被扣威望的账号)。 */
fun toReputation(raw: Double): Double = raw / REPUTATION_SCALE

/**
 * 威望文案,固定一位小数(设计稿楼层头 `威望 1.0`)。
 *
 * 收的是**已经除过 10** 的显示值——领域模型(`FloorUser.reputation`/`UserProfile.reputation`)
 * 里存的就是它,UI 不该再关心服务端那个原始整数。
 *
 * 用 `BigDecimal(double)` 的**精确**构造器 + [RoundingMode.HALF_UP] 复刻 JS 的
 * `Number.prototype.toFixed`:后者先取绝对值再「离得一样近时取大的那个」,
 * 等价于对二进制精确值做「远离零方向」的半进位。`String.format("%.1f", …)` 走的是
 * 另一条路(还要看 Locale),不用。
 */
fun formatReputation(reputation: Double): String {
  if (!reputation.isFinite()) return reputation.toString()
  return BigDecimal(reputation).setScale(1, RoundingMode.HALF_UP).toPlainString()
}

/** 金钱拆成金/银/铜三档。 */
data class Money(
  val gold: Long,
  val silver: Long,
  val copper: Long,
  /** 负数(NGA 的欠账)时为 true,三档取绝对值 */
  val negative: Boolean,
)

/**
 * 把服务端的铜币总数拆成金/银/铜。
 *
 * 负余额按绝对值拆再标记 [Money.negative]:直接对负数取模会拆出 `-1.-2.-3`
 * 这种读不出来的东西。小数先截断成整数铜币;非有限值(NaN/±∞)按 0 算
 * (TS 侧 `Number.isFinite(copperTotal) ? copperTotal : 0`,金样本里不允许非有限数字,
 * 这一档由手写单测锁)。
 */
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

/** 金钱文案,设计稿基础信息卡里写作 `金.银.铜`(样例 `0.0.0`)。 */
fun formatMoney(copperTotal: Double): String {
  val (gold, silver, copper, negative) = splitMoney(copperTotal)
  return "${if (negative) "-" else ""}$gold.$silver.$copper"
}
