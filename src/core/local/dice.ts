/**
 * 骰子复算（CONTEXT.md「骰子」）——`[dice]XdY[/dice]` 的点数服务端根本不下发，
 * 是网页版在浏览器里用一个以「作者 + 主题 + 楼层」为种子的伪随机数当场算出来的。
 * 想让客户端显示出和网页版同一组点数，只能把那段算法原样复刻一遍。
 *
 * 算法来源是 **NGA 官方前端** `js_bbscode_core.js` 里的 `ubbcode.sRand.rnd`
 * （https://img4.nga.cn/common_res/js_bbscode_core.js），与匿名还原、表情表同一条来源约定：
 * 只从官方脚本取，不碰 GPL-2.0 的第三方客户端代码。核心就三行：
 *
 * ```text
 * 种子 = authorId + tid + pid（+ 折叠块偏移）
 * 每投一次：种子 = (种子 * 9301 + 49297) % 233280，取 种子 / 233280 当 [0,1)
 * 点数 = floor(随机数 * 面数) + 1
 * ```
 *
 * 关键在于**一个楼层里的多颗骰子共用同一条数列**：第二颗骰子拿的是第一颗推进过的种子。
 * 所以复算的单位是「整个楼层」而不是单个 `[dice]`，`resolveDice` 收的是整棵 AST。
 *
 * 已知与网页版对不上的两处（都极罕见，`dice.test.ts` 的对拍用例覆盖不到）：
 *
 * 1. **匿名楼层**：种子里的 `authorId` 用的是响应给的 `authorid`，而匿名楼层那是
 *    `-1`、`-2` 这种页内序号，不是真 uid——网页版拿到的是哪个值没验证过。
 * 2. **三个 id 加起来正好是 0**：网页版此时改用 `Math.random()`，谁也复算不出来；
 *    这里保持确定性，照常往下算。
 */

import { childNodeLists, type BBCodeNode, type CollapseNode, type DiceNode } from '../bbcode'

/** 种子的三个来源，全部取服务端原值（`Floor.authorId` / tid / pid）。 */
export interface DiceSeed {
  readonly authorId: number
  readonly tid: number
  readonly pid: number
}

/** 展开式里的一项：一颗骰子，或一个常数（`[dice]20+1d80[/dice]` 里的 20）。 */
export type DiceTerm =
  | { readonly kind: 'roll'; readonly faces: number; readonly value: number }
  | { readonly kind: 'constant'; readonly value: number }

export interface DiceOutcome {
  /** AST 里的原始表达式，原样回显（网页版的 `ROLL : <表达式>`） */
  readonly expression: string
  readonly terms: readonly DiceTerm[]
  /** 超出 NGA 的上限时没有点数，网页版此处显示 `OUT OF LIMIT` / `ERROR` */
  readonly sum?: number
}

/** 一次最多 10 颗骰子（官方 `if($2>10 …)`）。 */
const MAX_DICE = 10
/** 面数上限（官方 `|| $4>100000`，等于 100000 仍然放行）。 */
const MAX_FACES = 100000

/** 官方那条把 `2d6+3` 拆成项的正则，连同它的宽松之处一起照搬。 */
const TERM_PATTERN = /(\+)(\d{0,10})(?:(d)(\d{1,10}))?/g

/**
 * 折叠块里的骰子会换一条数列——官方 `collapse.load` 给折叠块的渲染参数塞了
 * `seedOffset = 块序号 + 1`，且只在「新帖」上生效（老帖的点数不能因为这个改动而变）。
 */
const SEED_OFFSET_MIN_TID = 10246184
const SEED_OFFSET_MIN_PID = 200188932

/**
 * 一条伪随机数列。`state === 0` 表示还没起头——官方用的判据就是 `if(!arg.rndseed)`，
 * 所以数列**中途**恰好推到 0 时也会重新起头，这个怪癖一并保留。
 */
interface DiceStream {
  readonly origin: number
  state: number
}

function nextRandom(stream: DiceStream): number {
  if (stream.state === 0) stream.state = stream.origin
  stream.state = (stream.state * 9301 + 49297) % 233280
  return stream.state / 233280
}

/** 把一条表达式按官方规则拆项并逐颗投出来。数列状态留在 `stream` 里给下一条用。 */
function rollExpression(expression: string, stream: DiceStream): DiceOutcome {
  const terms: DiceTerm[] = []
  let sum = 0
  let outOfLimit = false

  for (const match of `+${expression}`.matchAll(TERM_PATTERN)) {
    const [, , countText = '', diceMark, facesText] = match
    // 官方：写了数量用数量，只写 `dY` 算一颗，两样都没有算常数 0
    const count = countText === '' ? (diceMark === undefined ? 0 : 1) : Number.parseInt(countText, 10)

    if (diceMark === undefined) {
      terms.push({ kind: 'constant', value: count })
      sum += count
      continue
    }

    const faces = Number.parseInt(facesText!, 10)
    if (count > MAX_DICE || faces > MAX_FACES) {
      outOfLimit = true
      continue
    }
    for (let i = 0; i < count; i++) {
      const value = Math.floor(nextRandom(stream) * faces) + 1
      terms.push({ kind: 'roll', faces, value })
      sum += value
    }
  }

  return { expression, terms, ...(outOfLimit ? {} : { sum }) }
}

/**
 * 复算一个楼层里的全部骰子。
 *
 * 返回的表以 AST 节点本身作 key——同一楼层里两处 `[dice]d100[/dice]` 文字一样、
 * 点数却不同，只有节点身份能把它们分开。渲染层拿 `parseBBCode` 的产物直接查。
 */
export function resolveDice(
  nodes: readonly BBCodeNode[],
  seed: DiceSeed,
): ReadonlyMap<DiceNode, DiceOutcome> {
  const outcomes = new Map<DiceNode, DiceOutcome>()
  const base = seed.authorId + seed.tid + seed.pid
  const offsetAllowed = seed.tid > SEED_OFFSET_MIN_TID || seed.pid > SEED_OFFSET_MIN_PID

  const fill = (scope: readonly BBCodeNode[], stream: DiceStream): void => {
    const collapses: CollapseNode[] = []

    // 第一趟：本层（跳过折叠块）的骰子按文档顺序共用一条数列。
    const visit = (list: readonly BBCodeNode[]): void => {
      for (const node of list) {
        if (node.type === 'dice') outcomes.set(node, rollExpression(node.expression, stream))
        else if (node.type === 'collapse') collapses.push(node)
        else for (const children of childNodeLists(node)) visit(children)
      }
    }
    visit(scope)

    // 第二趟：折叠块。网页版是点开时才渲染的，那时外层数列已经跑完，
    // 它 clone 的参数对象继承的就是跑完之后的种子；外层一颗骰子都没有时才轮到 seedOffset。
    collapses.forEach((node, index) => {
      const origin = stream.state !== 0 ? stream.state : base + (offsetAllowed ? index + 1 : 0)
      fill(node.children, { origin, state: stream.state })
    })
  }

  fill(nodes, { origin: base, state: 0 })
  return outcomes
}

/** 展开式的显示串，格式跟网页版一致：`d6(3)+d6(5)+2`。 */
export function formatDiceTerms(terms: readonly DiceTerm[]): string {
  return terms
    .map((term) => (term.kind === 'roll' ? `d${term.faces}(${term.value})` : String(term.value)))
    .join('+')
}
