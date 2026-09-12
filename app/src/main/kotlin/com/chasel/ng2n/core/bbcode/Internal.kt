package com.chasel.ng2n.core.bbcode

sealed interface ParseNode

sealed interface InternalNode : ParseNode {
  val children: List<ParseNode>
}

internal class ListItemNode(override val children: List<ParseNode>) : InternalNode

internal class TableRowFrame(override val children: List<ParseNode>) : InternalNode

internal class TableCellFrame(
  val colspan: Int,
  val rowspan: Int,
  val width: String?,
  override val children: List<ParseNode>,
) : InternalNode

internal class FragmentNode(override val children: List<ParseNode>) : InternalNode

internal class OpenTag(
  val name: String,
  val value: String?,
  val attrs: Map<String, String>?,
  val attrText: String?,
  val raw: String,
  val length: Int,
)
