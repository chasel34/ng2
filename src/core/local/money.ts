/**
 * 金钱与威望的显示规则（API 文档 §11.1）。
 *
 * 两条都是**纯显示换算**：服务端给的是一个整数，用户看到的是另一套单位。
 * 放 core/local 而不是各页面里各写一遍——楼层卡与资料页显示的必须是同一个数。
 */

/** 威望显示值 = 服务端 `rvrc`/`fame` ÷ 10。 */
export const REPUTATION_SCALE = 10

/** 1 银币 = 100 铜币，1 金币 = 100 银币（API 文档 §11.1：÷10000=金，余÷100=银，余=铜）。 */
const COPPER_PER_SILVER = 100
const COPPER_PER_GOLD = 10000

/** 服务端的 `rvrc`/`fame` → 显示用威望。可能是负数（被扣威望的账号）。 */
export function toReputation(raw: number): number {
  return raw / REPUTATION_SCALE
}

/**
 * 威望文案，固定一位小数（设计稿楼层头 `威望 1.0`）。
 *
 * 收的是**已经除过 10** 的显示值——领域模型（`FloorUser.reputation`/`UserProfile.reputation`）
 * 里存的就是它，UI 不该再关心服务端那个原始整数。
 */
export function formatReputation(reputation: number): string {
  return reputation.toFixed(1)
}

/** 金钱拆成金/银/铜三档。 */
export interface Money {
  readonly gold: number
  readonly silver: number
  readonly copper: number
  /** 负数（NGA 的欠账）时为 true，三档取绝对值 */
  readonly negative: boolean
}

/**
 * 把服务端的铜币总数拆成金/银/铜。
 *
 * 负余额按绝对值拆再标记 `negative`：直接对负数取模会拆出 `-1.-2.-3` 这种读不出来的东西。
 */
export function splitMoney(copperTotal: number): Money {
  const total = Math.trunc(Number.isFinite(copperTotal) ? copperTotal : 0)
  const magnitude = Math.abs(total)
  return {
    gold: Math.floor(magnitude / COPPER_PER_GOLD),
    silver: Math.floor((magnitude % COPPER_PER_GOLD) / COPPER_PER_SILVER),
    copper: magnitude % COPPER_PER_SILVER,
    negative: total < 0,
  }
}

/** 金钱文案，设计稿基础信息卡里写作 `金.银.铜`（样例 `0.0.0`）。 */
export function formatMoney(copperTotal: number): string {
  const { gold, silver, copper, negative } = splitMoney(copperTotal)
  return `${negative ? '-' : ''}${gold}.${silver}.${copper}`
}
