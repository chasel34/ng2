package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.CollapseNode
import com.chasel.ng2n.core.bbcode.DiceNode
import com.chasel.ng2n.core.bbcode.FloorRefNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.childNodeLists as astChildNodeLists
import com.chasel.ng2n.core.local.BBCodeShape
import com.chasel.ng2n.core.local.DiceOutcome
import com.chasel.ng2n.core.local.DiceScope
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.core.local.isReplyHeaderNode
import com.chasel.ng2n.core.local.quoteRefOf
import com.chasel.ng2n.core.local.replyHeaderRefOf
import com.chasel.ng2n.core.local.resolveDice
import kotlinx.serialization.SerialName

/**
 * 票 09 的 AST ↔ 票 10 的纯算法之间的适配层(票 11 Comments 票外 2 的收口)。
 *
 * 票 10 的 `core/local` 对节点类型是泛型的([BBCodeShape]),票 11 当时手写了一份
 * 临时实现(`ui/bbcode/ReplyHeader.kt`)。本票把两边接上:
 *
 * - [BBCodeNodeShape] 就是那个 `object : BBCodeShape<BBCodeNode>`;
 * - [diceScopeOf] 是票 10 KDoc 里说的「AST → [DiceScope] 抽取器」;
 * - [resolveFloorDice] 再把结果按**文档顺序**排回去 —— [RenderModelBuilder] 是按文档
 *   顺序递归的(碰到 `[collapse]` 当场展开它的内容),而 [DiceScope.flatten] 是
 *   「本层全部 → 再逐个折叠块」的顺序,两者在「折叠块出现在某颗顶层骰子之前」时不同。
 *   顺序对不上就是**点数贴错骰子**,所以这一步不能省(有单测钉住)。
 */

/** 节点类型名 = `@SerialName` 的字面量,与金样本里的 `type` 一致。 */
private fun typeNameOf(node: BBCodeNode): String {
  val annotation = node::class.java.getAnnotation(SerialName::class.java)
  return annotation?.value ?: node::class.java.simpleName
}

/**
 * 票 10 三个判据要的 AST 表面。
 *
 * 类型名走 `@SerialName` 反射一次即可(结果按类缓存),不再手写一张
 * 「类 → 字符串」的对照表 —— 那张表和 `Nodes.kt` 的注解迟早会走岔。
 */
object BBCodeNodeShape : BBCodeShape<BBCodeNode> {

  private val names = HashMap<Class<*>, String>()

  override fun typeOf(node: BBCodeNode): String =
    names.getOrPut(node.javaClass) { typeNameOf(node) }

  // ⚠ 必须用别名调顶层那一个:同名成员方法会把自己递归掉(实测 StackOverflowError)
  override fun childNodeLists(node: BBCodeNode): List<List<BBCodeNode>> = astChildNodeLists(node)

  override fun textValue(node: BBCodeNode): String? = (node as? TextNode)?.value

  override fun floorRefArgs(node: BBCodeNode): List<String> =
    (node as? FloorRefNode)?.args ?: emptyList()

  override fun floorRefPid(node: BBCodeNode): String? = (node as? FloorRefNode)?.pid
}

/** `[b]Reply to [pid=…]…[/b]` 回复头?渲染层拿它决定画成引用卡片还是普通粗体。 */
fun isReplyHeaderNode(node: BBCodeNode): Boolean = isReplyHeaderNode(node, BBCodeNodeShape)

/** 一个引用块指向哪一楼(认不出 `[pid]` 时为 null,「查看对话链」入口不画)。 */
fun quoteRefOf(node: BBCodeNode): QuoteRef? = quoteRefOf(node, BBCodeNodeShape)

/** 回复头指向哪一楼。 */
fun replyHeaderRefOf(node: BBCodeNode): QuoteRef? = replyHeaderRefOf(node, BBCodeNodeShape)

/**
 * AST → 骰子作用域:本层的 `[dice]` 表达式按文档顺序,折叠块各成一个子作用域。
 * 与 `core/local/Dice.kt` 的 [DiceScope] 约定逐字对齐(TS 侧 `resolveDice` 的两趟遍历)。
 *
 * 返回的 [IndexedDiceScope] 额外带着「每条表达式在**文档顺序**里排第几」,
 * 供 [resolveFloorDice] 把点数排回渲染器要的顺序。
 */
internal fun indexedDiceScopeOf(nodes: List<BBCodeNode>): IndexedDiceScope =
  collectDiceScope(nodes, intArrayOf(0))

/** AST → [DiceScope]。票 10 `resolveDice` 的入参就是它。 */
fun diceScopeOf(nodes: List<BBCodeNode>): DiceScope = indexedDiceScopeOf(nodes).scope

/** 一个作用域,外加它里面每条表达式的文档序号。 */
internal class IndexedDiceScope(
  val scope: DiceScope,
  private val documentIndices: List<Int>,
  private val children: List<IndexedDiceScope>,
) {
  /** 与 [DiceScope.flatten] 同序的文档序号序列。 */
  fun flattenDocumentIndices(): List<Int> = buildList {
    addAll(documentIndices)
    children.forEach { addAll(it.flattenDocumentIndices()) }
  }
}

private fun collectDiceScope(nodes: List<BBCodeNode>, counter: IntArray): IndexedDiceScope {
  val expressions = ArrayList<String>()
  val documentIndices = ArrayList<Int>()
  val children = ArrayList<IndexedDiceScope>()

  fun visit(list: List<BBCodeNode>) {
    for (node in list) {
      when (node) {
        is DiceNode -> {
          expressions.add(node.expression)
          // 计数器在这里走,而不是在 flatten 时 —— 折叠块是就地递归的,
          // 所以这个序号就是「渲染器会第几个碰到它」
          documentIndices.add(counter[0]++)
        }
        // 折叠块另起一条数列(官方 collapse.load 的 seedOffset),所以是子作用域
        is CollapseNode -> children.add(collectDiceScope(node.children, counter))
        else -> for (nested in astChildNodeLists(node)) visit(nested)
      }
    }
  }

  visit(nodes)
  return IndexedDiceScope(DiceScope(expressions, children.map { it.scope }), documentIndices, children)
}

/**
 * 复算一个楼层的骰子,并按**文档顺序**返回 —— 正是 [RenderModelBuilder] 取用的顺序
 * (`options.dice.getOrNull(diceIndex++)`)。
 */
fun resolveFloorDice(nodes: List<BBCodeNode>, seed: DiceSeed): List<DiceOutcome> {
  val indexed = indexedDiceScopeOf(nodes)
  val outcomes = resolveDice(indexed.scope, seed)
  val documentIndices = indexed.flattenDocumentIndices()
  if (outcomes.isEmpty()) return emptyList()

  val ordered = arrayOfNulls<DiceOutcome>(outcomes.size)
  documentIndices.forEachIndexed { position, target ->
    val outcome = outcomes.getOrNull(position) ?: return@forEachIndexed
    if (target in ordered.indices) ordered[target] = outcome
  }
  // 理论上不会有空位(两个序列等长、序号是 0..n-1 的一个排列);真有就退回原序,不吞骰子
  return ordered.mapIndexed { index, outcome -> outcome ?: outcomes[index] }
}
