package com.chasel.ng2n.core.bbcode

/**
 * 解析期的节点。直译 `src/core/bbcode/internal.ts` 的 `InternalNode`。
 *
 * `[*]`、`[tr]`、`[td]` 单独拿出来没有意义,它们由父级的 `[list]`/`[table]` 收集掉;
 * `__fragment` 则是 `[stripbr]` 这种只加工内容、自己不留痕迹的标签。没被父级收走的,
 * 归一化时摊平成内容。
 *
 * [BBCodeNode] 也是 [ParseNode] —— 解析栈上两者混装,归一化时才收敛成纯 AST。
 */
sealed interface ParseNode

/** 只在解析过程中存在的中间节点。归一化会把它们**彻底摊平**(见 `Normalize.kt` 的说明)。 */
sealed interface InternalNode : ParseNode {
  val children: List<ParseNode>
}

/** `[*]` 的一条。 */
internal class ListItemNode(override val children: List<ParseNode>) : InternalNode

/** `[tr]`。 */
internal class TableRowFrame(override val children: List<ParseNode>) : InternalNode

/** `[td]`。`colspan`/`rowspan`/`width` 在这里就算好,折成 [TableCell] 时直接搬。 */
internal class TableCellFrame(
  val colspan: Int,
  val rowspan: Int,
  val width: String?,
  override val children: List<ParseNode>,
) : InternalNode

/** `[stripbr]`:只加工内容,自己不留痕迹。 */
internal class FragmentNode(override val children: List<ParseNode>) : InternalNode

/** 扫描到的一个开标签。直译 `internal.ts` 的 `OpenTag`,`undefined` 一律换成 `null`。 */
internal class OpenTag(
  /** 小写标签名。 */
  val name: String,
  /** `[tag=value]` 里的 value;`null` = 没有 `=` 参数(与 `""` 不同,`[collapse=]` 是 `""`)。 */
  val value: String?,
  /** `[td colspan=2 width=100]` 这类空格分隔的属性。 */
  val attrs: Map<String, String>?,
  /** 空格之后的原始属性文本,给 `[dice 2d6]` 这种不是 kv 形式的用。 */
  val attrText: String?,
  /** 原始开标签文本,标签未闭合时按原样降级成文本用。 */
  val raw: String,
  /** 开标签在原文里占的字符数。 */
  val length: Int,
)
