package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.bbcode.*
import com.chasel.ng2n.core.local.*
import java.time.Instant

const val PERSONA_SAMPLE_LIMIT = 300
const val PERSONA_SAMPLE_TEXT_LIMIT = 2000
const val PERSONA_INLINE_LIMIT = 16000
const val PERSONA_MATERIAL_LIMIT = 64000
// 续读一次取回剩余全部样本，首轮报告只需「技能正文 + 一次续读 + 按需补读主楼」即可在运行上限内完成。
const val PERSONA_HISTORY_PAGE = PERSONA_MATERIAL_LIMIT + 4000

fun canAnalyzePersona(uid: Long) = uid > 0
fun personaPostKey(topic: Topic) = userPostKey(topic)
fun personaPostTime(topic: Topic) = topic.reply?.postedAt ?: topic.postedAt
fun mergePersonaPosts(posts: List<Topic>): List<Topic> = posts.sortedByDescending(::personaPostTime)
  .distinctBy(::personaPostKey).take(PERSONA_SAMPLE_LIMIT)

fun personaPostAccessible(topic: Topic): Boolean = !topic.denied && !topic.anonymous && (topic.authorId ?: 1) > 0
fun personaPostBlockedByRules(topic: Topic, rules: List<FilterRule>): Boolean =
  matchFilterRules(rules, FilterSubject(author = topic.author, authorId = topic.authorId,
    title = topic.subject, content = topic.reply?.content)) != null
fun personaPostAllowed(topic: Topic, rules: List<FilterRule>): Boolean =
  personaPostAccessible(topic) && !personaPostBlockedByRules(topic, rules)

private fun personaText(nodes: List<BBCodeNode>): String = nodes.joinToString("") { node -> when (node) {
  is TextNode -> node.value
  is CodeNode -> node.value
  LineBreakNode, DividerNode -> "\n"
  is ImageNode, is AlbumNode -> "[图片未读取]"
  is QuoteNode -> "[引用他人，非本人立场]${personaText(node.children)}[引用结束]"
  is LinkNode -> personaText(node.children) + " (${node.href})"
  is MentionNode -> node.username
  else -> childNodeLists(node).joinToString("\n") { personaText(it) }
} }

private fun personaSampleText(topic: Topic): String {
  val text = if (topic.reply == null) "标题：${personaText(parseBBCode(topic.subject))}"
    else personaText(parseBBCode(topic.reply.content))
  return if (text.length <= PERSONA_SAMPLE_TEXT_LIMIT) text
    else text.take(PERSONA_SAMPLE_TEXT_LIMIT) + "…（本条只保留前 $PERSONA_SAMPLE_TEXT_LIMIT 字，需要原句用 read_floor 重新读取）"
}

fun personaInlineMaterial(material: String): String = if (material.length <= PERSONA_INLINE_LIMIT) material
  else material.take(PERSONA_INLINE_LIMIT) +
    "\n（本轮只内联前 $PERSONA_INLINE_LIMIT 字，其余 ${material.length - PERSONA_INLINE_LIMIT} 字未内联；" +
    "用 read_user_history 从 offset=$PERSONA_INLINE_LIMIT 续读，未读部分不得当作已分析。）"

fun buildPersonaContext(posts: List<Topic>, name: String, rules: List<FilterRule> = emptyList(), missing: List<String> = emptyList()): TopicContext {
  val unique = posts.distinctBy(::personaPostKey)
  val inaccessible = unique.count { !personaPostAccessible(it) }
  val blocked = unique.count { personaPostAccessible(it) && personaPostBlockedByRules(it, rules) }
  val merged = mergePersonaPosts(unique.filter { personaPostAllowed(it, rules) })

  // 实际进入请求的是整份 material()，它还包含范围明细与每条样本的来源标注，因此长度判据不能只看正文字数。
  fun build(selected: List<Topic>, dropped: Int, truncated: Boolean, total: Int): TopicContext {
    val sources = selected.mapIndexed { index, topic -> AiSource("s${index + 1}", topic.tid, topic.reply?.pid ?: 0,
      if (topic.reply == null || topic.reply.pid == 0L) 0 else -1, 1, name,
      Instant.ofEpochSecond(personaPostTime(topic)).toString(), personaSampleText(topic),
      emptyList(), if (topic.reply == null) "summary" else "floor") }
    val replies = selected.count { it.reply != null }
    val span = if (selected.isEmpty()) "无可访问样本" else "${sources.last().postedAt.take(10)} 至 ${sources.first().postedAt.take(10)}"
    return TopicContext(sources, null, blocked, emptyList(), title = name, entryKind = "个人",
      currentScope = "按时间从近到远，主题与回复合计最多 $PERSONA_SAMPLE_LIMIT 条独立发言",
      ranges = listOf(
        AiReadingRow("历史回复", "$replies 条", listOf("正文" to "随搜索结果带回")),
        AiReadingRow("历史主题", "${selected.size - replies} 个", listOf("首次读取" to "标题", "补读主楼" to "按问题需要"), pill = "仅标题"),
        AiReadingRow("合计", "${selected.size} / $PERSONA_SAMPLE_LIMIT 条", listOf("时间跨度" to span, "顺序" to "从近到远",
          "去重" to "按独立发言", "样本正文" to "${sources.sumOf { it.text.length }} 字",
          "首轮内联" to if (truncated) "前 $PERSONA_INLINE_LIMIT 字 / 共约 $total 字" else "全部内联"), expanded = true)) +
        (if (dropped > 0) listOf(AiReadingRow("长度上限", "$dropped 条", listOf("原因" to "超出单次可读长度", "取舍" to "保留较新发言"),
          skipped = true, pill = "未纳入")) else emptyList()) +
        listOf(AiReadingRow("屏蔽规则", "$blocked 条", skipped = true, pill = "未计入"),
          AiReadingRow("匿名或无权限", "$inaccessible 条", skipped = true, pill = "未计入"),
          AiReadingRow("图片", "不读取", skipped = true, pill = "本期仅文本")),
      sampleCount = selected.size, missing = missing,
      note = "只整理本人明确表达过的内容。主题仅标题，主楼正文由 agent 按需读取。读取不等于已成功处理，结论仅覆盖报告确认已处理的样本。" +
        (if (inaccessible > 0) "匿名或无权限发言 $inaccessible 条不在样本内，不是屏蔽规则命中。" else "") +
        (if (dropped > 0) "较早的 $dropped 条发言超出单次可读长度，未纳入本次样本。" else "") +
        (if (truncated) "首轮只内联前 $PERSONA_INLINE_LIMIT 字，其余样本用 read_user_history 一次续读取回。" else "") +
        if (missing.isEmpty()) "更早或不可访问历史不在样本内。" else "历史读取不完整：${missing.joinToString("；")}。不能视为最近完整样本。")
  }

  var selected = merged
  var context = build(selected, 0, false, 0)
  var total = context.material().length
  // 留出余量给截断说明，保证最终 material 仍在上限内，续读一次即可取回剩余部分。
  val target = PERSONA_MATERIAL_LIMIT - 500
  while (total > target && selected.size > 1) {
    val drop = (((total - target).toLong() * selected.size) / total).toInt().coerceAtLeast(1)
    selected = selected.dropLast(drop)
    context = build(selected, merged.size - selected.size, false, 0)
    total = context.material().length
  }
  return if (total <= PERSONA_INLINE_LIMIT) context else build(selected, merged.size - selected.size, true, total)
}
