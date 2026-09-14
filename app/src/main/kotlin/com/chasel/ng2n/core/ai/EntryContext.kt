package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*

@kotlinx.serialization.Serializable
data class AiReadingRow(val label: String, val amount: String, val details: List<Pair<String, String>> = emptyList(),
  val skipped: Boolean = false, val pill: String? = null, val expanded: Boolean = false)

fun buildListContext(topics: List<Topic>, title: String, sort: String, rules: List<FilterRule> = emptyList()): TopicContext {
  val eligible = topics.filter { it.shortcut == null && it.jumpUrl == null }
  val filtered = eligible.filter { !it.denied && matchFilterRules(rules,
    FilterSubject(author = it.author, authorId = it.authorId, title = it.subject)) == null }
  val selected = filtered.take(300)
  val blocked = eligible.size - filtered.size
  val anonymousNames = topics.filter { it.anonymous || (it.authorId ?: 0) < 0 }.map { it.author }.filter { it.isNotBlank() }
  fun anonymize(value: String) = anonymousNames.fold(value.replace(Regex("#anony_[0-9a-f]{32}"), "匿名用户")) { text, name -> text.replace(name, "匿名用户") }
  val sources = selected.mapIndexed { index, topic ->
    AiSource("s${index + 1}", topic.tid, 0, 0, 1, if (topic.anonymous || (topic.authorId ?: 0) < 0) "匿名用户" else topic.author,
      java.time.Instant.ofEpochSecond(topic.postedAt).toString(),
      "标题：${anonymize(topic.subject)}\n回复数：${topic.replies}\n最后回复时间：${java.time.Instant.ofEpochSecond(topic.lastPostAt)}", emptyList(), "summary")
  }
  return TopicContext(sources, null, blocked, emptyList(), title = title, currentScope = sort, entryKind = "列表",
    ranges = listOf(
      AiReadingRow("当前列表", sort, listOf("已加载" to "${eligible.size} 个主题", "计入" to "靠前 ${selected.size} 个", "排序与筛选" to "保持不变"), expanded = true),
      AiReadingRow("主题摘要", "${selected.size} 个"),
      AiReadingRow("屏蔽规则", "$blocked 个", skipped = true, pill = "未计入")),
    note = "正文和楼层不默认读取，agent 按问题再读，受限速和额度约束。")
}

fun buildChainContext(first: TopicDetail, details: List<TopicDetail>, chain: List<ChainNode>,
  rules: List<FilterRule> = emptyList(), urls: AttachmentUrls = DefaultAttachmentUrls): TopicContext {
  val contexts = listOf(buildTopicContext(first, first, 0, rules, urls)) + chain.distinctBy { it.pid }.filter { it.pid != 0L }.mapNotNull { node ->
    details.firstOrNull { detail -> (detail.floors + detail.hotReplies).any { it.pid == node.pid } }?.let { detail ->
      buildTopicContext(first.copy(floors = emptyList(), hotReplies = emptyList()), detail, node.pid, rules, urls)
    }
  }
  val sources = contexts.flatMap { it.sources }.distinctBy { Triple(it.tid, it.pid, it.part) }
    .mapIndexed { index, source -> source.copy(id = "s${index + 1}") }
  val chainIds = chain.map { it.pid }.toSet()
  val chainSources = sources.filter { it.pid in chainIds }
  val image = chainSources.firstOrNull { it.images.isNotEmpty() }?.images?.firstOrNull()
  // 首轮图片只从链内发言选取，主楼图片因此不计入图片总数，只单独说明。
  val rootImages = (sources - chainSources.toSet()).flatMap { it.images }.distinct()
    .minus(chainSources.flatMap { it.images }.toSet()).size
  val visible = sources.filter { it.part == "floor" }.associateBy { it.pid }
  val blocked = contexts.sumOf { it.blocked }
  val missing = chain.count { node -> details.none { detail -> (detail.floors + detail.hotReplies).any { it.pid == node.pid } } }
  val roles = listOf(ChainRole.UPSTREAM to "上游", ChainRole.CURRENT to "当前", ChainRole.DOWNSTREAM to "下游").map { (role, label) ->
    label to chain.filter { it.role == role }.joinToString("、") { node -> visible[node.pid]?.let { "${it.floor} 楼" } ?: "未计入" }.ifEmpty { "无" }
  }
  val count = chain.count { it.pid in visible }
  return TopicContext(sources, image, blocked, sources.map { it.page }.distinct(), title = contexts.first().title,
    currentScope = "主楼正文与当前回复链全部发言；不加入第一页其他楼层", entryKind = "回复链",
    ranges = listOf(AiReadingRow("主楼正文", if (0L in visible) "已计入" else "未计入",
        visible[0L]?.let { listOf("楼层" to "${it.floor} 楼") }.orEmpty(), skipped = 0L !in visible),
      AiReadingRow("回复链", "$count 层", roles, expanded = true),
      imageReadingRow(chainSources, image,
        extra = if (rootImages > 0) listOf("主楼图片" to "$rootImages 张，不参与首轮选取") else emptyList())) +
      if (blocked > 0 || missing > 0) listOf(AiReadingRow("屏蔽规则", "$blocked 条", skipped = true, pill = "未计入"),
        AiReadingRow("缺失楼层", "$missing 条", skipped = true, pill = "未读取")) else emptyList(),
    note = if (missing > 0) "当前回复链有 $missing 条发言未读取，不能视为完整回复链。" else null)
}

// sources 必须只含参与首轮选取的发言，总数与「已带入」才是同一口径；范围外的图片用 extra 单独说明。
fun imageReadingRow(sources: List<AiSource>, image: String?, reason: String = "从回复链内选取",
  extra: List<Pair<String, String>> = emptyList()): AiReadingRow {
  val total = sources.flatMap { it.images }.distinct().size
  val read = if (image == null) 0 else 1
  return AiReadingRow("图片", if (total == 0) "0 张" else "$read / $total 张",
    (if (total == 0) emptyList() else listOf("已带入" to if (image == null) "无" else reason, "未读取" to "${total - read} 张，需要时再读")) + extra,
    expanded = total > 0 || extra.isNotEmpty())
}
