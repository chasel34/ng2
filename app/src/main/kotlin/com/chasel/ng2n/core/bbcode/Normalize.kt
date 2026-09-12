package com.chasel.ng2n.core.bbcode

/**
 * 合并相邻文本,并摊平任何没被父标签收走的中间节点。直译 `src/core/bbcode/normalize.ts`。
 *
 * 相邻文本是解析过程的常态:未知标签透传成文本、未闭合标签降级成文本,都会在已有文本
 * 旁边再落一段。渲染层不该关心这些,统一在这里收干净。
 *
 * **递归深度**:`normalize` 递归的是 AST 深度,而 AST 深度由解析器的
 * `MAX_NESTING_DEPTH = 64` 卡死(唯一能再叠一层的是行首 `===标题===`,它递归调一次
 * `parseBBCode`,且标题内容永远不会再以 `=` 开头 —— `={3,}` 贪婪吃光行首那串等号,
 * 捕获组必然从非 `=` 字符起步,所以标题不会自嵌套)。最坏 64 + 1 + 64 ≈ 129 层,
 * `deep-nesting-5000` 那条金样本(5000 层 `[b]`)在这里只会看到 64 层,不爆栈。
 *
 * **与 RN 版的有意偏离**:TS 的摊平只做一层,`[tr][td]a[/td][/tr]`(没有外层
 * `[table]`)会把 `__td` 这种解析期中间节点漏进 AST —— 它不在 29 种节点里,序列化
 * 进帖子缓存就是脏数据。这里改成**递归摊平**,中间节点一个都不留。有 `[table]`/`[list]`
 * 包着的正常形态两边结果完全一致(全部金样本为证)。
 */
internal fun normalize(nodes: List<ParseNode>): List<BBCodeNode> {
  val flattened = ArrayList<BBCodeNode>(nodes.size)
  flattenInto(nodes, flattened)

  val merged = ArrayList<BBCodeNode>(flattened.size)
  for (node in flattened) {
    val previous = merged.lastOrNull()
    if (node is TextNode && previous is TextNode) {
      merged[merged.size - 1] = TextNode(previous.value + node.value)
      continue
    }
    merged.add(if (node is ChildBearing) node.withChildren(normalize(node.children)) else node)
  }
  return merged
}

private fun flattenInto(nodes: List<ParseNode>, out: MutableList<BBCodeNode>) {
  for (node in nodes) {
    when (node) {
      is InternalNode -> flattenInto(node.children, out)
      is BBCodeNode -> out.add(node)
    }
  }
}

/**
 * 取子树里的可见文字,用于只需要一个字符串的节点(如 `[@]名字[/@]`)。
 *
 * 照 TS 只走 `children`:`list` 的 items 与 `table` 的 cells **不进**结果。
 */
internal fun plainText(nodes: List<BBCodeNode>): String {
  val out = StringBuilder()
  appendPlainText(nodes, out)
  return out.toString()
}

private fun appendPlainText(nodes: List<BBCodeNode>, out: StringBuilder) {
  for (node in nodes) {
    when (node) {
      is TextNode -> out.append(node.value)
      is ChildBearing -> appendPlainText(node.children, out)
      else -> Unit
    }
  }
}
