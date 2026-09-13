package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.CollapseNode
import com.chasel.ng2n.core.bbcode.DiceNode
import com.chasel.ng2n.core.bbcode.FloorRefNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.TopicRefNode
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

private fun typeNameOf(node: BBCodeNode): String {
  val annotation = node::class.java.getAnnotation(SerialName::class.java)
  return annotation?.value ?: node::class.java.simpleName
}

object BBCodeNodeShape : BBCodeShape<BBCodeNode> {

  private val names = HashMap<Class<*>, String>()

  override fun typeOf(node: BBCodeNode): String =
    names.getOrPut(node.javaClass) { typeNameOf(node) }

  override fun childNodeLists(node: BBCodeNode): List<List<BBCodeNode>> = astChildNodeLists(node)

  override fun textValue(node: BBCodeNode): String? = (node as? TextNode)?.value

  override fun floorRefArgs(node: BBCodeNode): List<String> =
    (node as? FloorRefNode)?.args ?: emptyList()

  override fun floorRefPid(node: BBCodeNode): String? = (node as? FloorRefNode)?.pid

  override fun topicRefTid(node: BBCodeNode): String? = (node as? TopicRefNode)?.tid
}

fun isReplyHeaderNode(node: BBCodeNode): Boolean = isReplyHeaderNode(node, BBCodeNodeShape)

fun quoteRefOf(node: BBCodeNode): QuoteRef? = quoteRefOf(node, BBCodeNodeShape)

fun replyHeaderRefOf(node: BBCodeNode): QuoteRef? = replyHeaderRefOf(node, BBCodeNodeShape)

internal fun indexedDiceScopeOf(nodes: List<BBCodeNode>): IndexedDiceScope =
  collectDiceScope(nodes, intArrayOf(0))

fun diceScopeOf(nodes: List<BBCodeNode>): DiceScope = indexedDiceScopeOf(nodes).scope

internal class IndexedDiceScope(
  val scope: DiceScope,
  private val documentIndices: List<Int>,
  private val children: List<IndexedDiceScope>,
) {
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
          documentIndices.add(counter[0]++)
        }
        is CollapseNode -> children.add(collectDiceScope(node.children, counter))
        else -> for (nested in astChildNodeLists(node)) visit(nested)
      }
    }
  }

  visit(nodes)
  return IndexedDiceScope(DiceScope(expressions, children.map { it.scope }), documentIndices, children)
}

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
  return ordered.mapIndexed { index, outcome -> outcome ?: outcomes[index] }
}
