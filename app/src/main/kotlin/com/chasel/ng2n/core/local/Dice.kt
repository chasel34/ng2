package com.chasel.ng2n.core.local

/**
 * 骰子复算。直译 `src/core/local/dice.ts`。
 *
 * `[dice]XdY[/dice]` 的点数服务端根本不下发,是网页版在浏览器里用一个以
 * 「作者 + 主题 + 楼层」为种子的伪随机数当场算出来的。想跟网页版显示同一组点数,
 * 只能把那段算法原样复刻。算法来源是 **NGA 官方前端** `js_bbscode_core.js` 的
 * `ubbcode.sRand.rnd`,与匿名还原、表情表同一条来源约定:只从官方脚本取,
 * 不碰 GPL-2.0 的第三方客户端代码。核心就三行:
 *
 * ```text
 * 种子 = authorId + tid + pid(+ 折叠块偏移)
 * 每投一次:种子 = (种子 * 9301 + 49297) % 233280,取 种子 / 233280 当 [0,1)
 * 点数 = floor(随机数 * 面数) + 1
 * ```
 *
 * 关键在于**一个楼层里的多颗骰子共用同一条数列**:第二颗骰子拿的是第一颗推进过的种子。
 * 所以复算的单位是「整个楼层」而不是单个 `[dice]`。
 *
 * 已知与网页版对不上的两处(都极罕见,RN 侧对拍用例也覆盖不到):
 *
 * 1. **匿名楼层**:种子里的 `authorId` 用的是响应给的 `authorid`,而匿名楼层那是
 *    `-1`、`-2` 这种页内序号,不是真 uid——网页版拿到的是哪个值没验证过。
 * 2. **三个 id 加起来正好是 0**:网页版此时改用 `Math.random()`,谁也复算不出来;
 *    这里保持确定性,照常往下算。
 *
 * ## 输入形态与票 09 的关系
 *
 * TS 版 `resolveDice(nodes, seed)` 直接吃 BBCode AST,返回 `Map<DiceNode, DiceOutcome>`。
 * 票 09 的 AST 还没合并,而且 Kotlin 的 data class 是结构相等、当不了身份键,
 * 所以这里把输入抠成 [DiceScope]:**「本作用域内按文档顺序排好的 dice 表达式」+
 * 「折叠块子作用域」**——正是算法真正需要的两样东西。票 11/13 接正式解析器时
 * 写一个 AST → [DiceScope] 的抽取器即可(遍历顺序见 [DiceScope] 的 KDoc)。
 */

/** 种子的三个来源,全部取服务端原值(`Floor.authorId` / tid / pid)。 */
data class DiceSeed(
  val authorId: Long,
  val tid: Long,
  val pid: Long,
)

/** 展开式里的一项:一颗骰子,或一个常数(`[dice]20+1d80[/dice]` 里的 20)。 */
sealed interface DiceTerm {
  val value: Long

  /** 投出来的一颗骰子。 */
  data class Roll(val faces: Long, override val value: Long) : DiceTerm

  /** 表达式里的常数项。 */
  data class Constant(override val value: Long) : DiceTerm
}

data class DiceOutcome(
  /** AST 里的原始表达式,原样回显(网页版的 `ROLL : <表达式>`) */
  val expression: String,
  val terms: List<DiceTerm>,
  /** 超出 NGA 的上限时没有点数(`null`),网页版此处显示 `OUT OF LIMIT` / `ERROR` */
  val sum: Long?,
)

/**
 * 一个作用域里的骰子。折叠块**另起一条数列**,所以它是嵌套结构而不是一张平表。
 *
 * - [expressions]:本作用域(**跳过折叠块子树**)里的 dice 表达式,按文档顺序;
 * - [collapses]:本作用域里的折叠块,按文档顺序,每个是一个子作用域。
 *
 * [resolveDice] 的返回顺序 = 先本作用域的 [expressions],再逐个折叠块递归展开
 * (与 TS 版 `Map` 的插入顺序一致);[flatten] 给出同一顺序的表达式序列,
 * 票 11/13 拿它把结果贴回 AST 节点。
 */
data class DiceScope(
  val expressions: List<String> = emptyList(),
  val collapses: List<DiceScope> = emptyList(),
) {
  /** 与 [resolveDice] 返回值同序的表达式序列。 */
  fun flatten(): List<String> = buildList {
    addAll(expressions)
    collapses.forEach { addAll(it.flatten()) }
  }
}

/** 一次最多 10 颗骰子(官方 `if($2>10 …)`)。 */
private const val MAX_DICE = 10L

/** 面数上限(官方 `|| $4>100000`,等于 100000 仍然放行)。 */
private const val MAX_FACES = 100000L

/** 官方那条把 `2d6+3` 拆成项的正则,连同它的宽松之处一起照搬。 */
private val TERM_PATTERN = Regex("""(\+)(\d{0,10})(?:(d)(\d{1,10}))?""")

/**
 * 折叠块里的骰子会换一条数列——官方 `collapse.load` 给折叠块的渲染参数塞了
 * `seedOffset = 块序号 + 1`,且只在「新帖」上生效(老帖的点数不能因为这个改动而变)。
 */
private const val SEED_OFFSET_MIN_TID = 10246184L
private const val SEED_OFFSET_MIN_PID = 200188932L

private const val LCG_MULTIPLIER = 9301L
private const val LCG_INCREMENT = 49297L
private const val LCG_MODULUS = 233280L

/**
 * 一条伪随机数列。`state == 0` 表示还没起头——官方用的判据就是 `if(!arg.rndseed)`,
 * 所以数列**中途**恰好推到 0 时也会重新起头,这个怪癖一并保留。
 */
private class DiceStream(val origin: Long, var state: Long)

private fun nextRandom(stream: DiceStream): Double {
  if (stream.state == 0L) stream.state = stream.origin
  stream.state = (stream.state * LCG_MULTIPLIER + LCG_INCREMENT) % LCG_MODULUS
  return stream.state.toDouble() / LCG_MODULUS.toDouble()
}

/** 把一条表达式按官方规则拆项并逐颗投出来。数列状态留在 `stream` 里给下一条用。 */
private fun rollExpression(expression: String, stream: DiceStream): DiceOutcome {
  val terms = ArrayList<DiceTerm>()
  var sum = 0L
  var outOfLimit = false

  for (match in TERM_PATTERN.findAll("+$expression")) {
    val countText = match.groupValues[2]
    val diceMark = match.groups[3]
    val facesText = match.groupValues[4]
    // 官方:写了数量用数量,只写 `dY` 算一颗,两样都没有算常数 0
    val count = if (countText.isEmpty()) {
      if (diceMark == null) 0L else 1L
    } else {
      countText.toLong()
    }

    if (diceMark == null) {
      terms.add(DiceTerm.Constant(count))
      sum += count
      continue
    }

    val faces = facesText.toLong()
    if (count > MAX_DICE || faces > MAX_FACES) {
      outOfLimit = true
      continue
    }
    repeat(count.toInt()) {
      val value = kotlin.math.floor(nextRandom(stream) * faces.toDouble()).toLong() + 1L
      terms.add(DiceTerm.Roll(faces, value))
      sum += value
    }
  }

  return DiceOutcome(expression, terms, if (outOfLimit) null else sum)
}

/**
 * 复算一个楼层里的全部骰子,按文档顺序返回(同 TS 版 `Map` 的插入顺序)。
 *
 * 顺序 = 本作用域的骰子,再逐个折叠块递归——与 [DiceScope.flatten] 一致。
 */
fun resolveDice(scope: DiceScope, seed: DiceSeed): List<DiceOutcome> {
  val outcomes = ArrayList<DiceOutcome>()
  val base = seed.authorId + seed.tid + seed.pid
  val offsetAllowed = seed.tid > SEED_OFFSET_MIN_TID || seed.pid > SEED_OFFSET_MIN_PID

  fun fill(current: DiceScope, stream: DiceStream) {
    // 第一趟:本层(跳过折叠块)的骰子按文档顺序共用一条数列。
    for (expression in current.expressions) {
      outcomes.add(rollExpression(expression, stream))
    }

    // 第二趟:折叠块。网页版是点开时才渲染的,那时外层数列已经跑完,
    // 它 clone 的参数对象继承的就是跑完之后的种子;外层一颗骰子都没有时才轮到 seedOffset。
    current.collapses.forEachIndexed { index, child ->
      val origin = if (stream.state != 0L) {
        stream.state
      } else {
        base + (if (offsetAllowed) index + 1L else 0L)
      }
      fill(child, DiceStream(origin, stream.state))
    }
  }

  fill(scope, DiceStream(base, 0L))
  return outcomes
}

/** 展开式的显示串,格式跟网页版一致:`d6(3)+d6(5)+2`。 */
fun formatDiceTerms(terms: List<DiceTerm>): String = terms.joinToString("+") { term ->
  when (term) {
    is DiceTerm.Roll -> "d${term.faces}(${term.value})"
    is DiceTerm.Constant -> term.value.toString()
  }
}
