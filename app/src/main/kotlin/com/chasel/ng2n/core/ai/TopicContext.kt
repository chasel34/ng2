package com.chasel.ng2n.core.ai

import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.bbcode.*
import com.chasel.ng2n.core.local.*

@kotlinx.serialization.Serializable
data class AiSource(val id: String, val tid: Long, val pid: Long, val floor: Long, val page: Int,
  val author: String, val postedAt: String, val text: String, val images: List<String>, val part: String? = null,
  val web: AiWebSource? = null)
@kotlinx.serialization.Serializable
// prepared 是本次准备范围的条数，与范围卡其余数字同口径；sources 会随 agent 续读增长，不能用它替代。
// missing 记录准备阶段确实失败的读取，样本为 0 时用它区分「读取失败」与「没有可访问发言」。
data class TopicContext(val sources: List<AiSource>, val image: String?, val blocked: Int, val pages: List<Int>, val imageInput: String? = image, val title: String = "", val currentScope: String? = null, val entryKind: String = "主题", val ranges: List<AiReadingRow> = emptyList(), val note: String? = null, val sampleCount: Int = 0, val prepared: Int = sources.size, val missing: List<String> = emptyList()) {
  fun material(): String = buildString {
    appendLine("${when (entryKind) { "列表" -> "列表"; "个人" -> "用户"; else -> "主题" }}：$title")
    currentScope?.let { appendLine("当前入口范围：$it") }
    if (entryKind == "列表") appendLine("仅使用主题摘要；正文与楼层尚未读取。屏蔽 $blocked 个主题未计入。")
    else if (entryKind == "个人") appendLine("个人历史文本样本 ${sources.size} 条；屏蔽 $blocked 条未计入。读取不代表已成功分析，不代表完整历史。")
    else appendLine("实际阅读页码：${pages.joinToString()}；屏蔽 ${blocked} 条未计入。仅分析以下资料，不代表完整主题。")
    if (entryKind !in listOf("列表", "个人") && sources.none { it.floor == 0L }) appendLine("主楼不可用或已被屏蔽，本次未读主楼正文。")
    note?.let { appendLine(it) }
    ranges.forEach { appendLine("${it.label}：${it.amount}；${it.details.joinToString { detail -> "${detail.first}：${detail.second}" }}") }
    sources.forEach { s ->
      if (s.web != null) {
        appendLine("来源 ${s.id}，站外网页 ${webDomain(s.web.url)}，${s.web.title}，${webReadState(s.web)}")
        appendLine(s.text)
        return@forEach
      }
      appendLine("来源 ${s.id}，主题 ${s.tid}，${when {
        s.part == "summary" -> "仅标题摘要，未读正文"
        s.floor < 0 -> "回复 ${s.pid}，页码未知"
        else -> "第 ${s.page} 页，${s.floor} 楼"
      }}，${s.author}，${s.postedAt}")
      appendLine(s.text)
      s.images.forEach { appendLine("图片索引：$it（${if (it == image) "本次带入" else "未读取"}）") }
    }
  }
}

fun sourceFloorLabel(floor: Long): String = if (floor < 0) "回复" else "$floor 楼"
fun sourceJumpLabel(floor: Long): String = when {
  floor == 0L -> "跳到主楼"
  floor < 0 -> "跳到该回复"
  else -> "跳到 $floor 楼"
}

fun buildTopicContext(first: TopicDetail, current: TopicDetail, selectedPid: Long? = null,
  rules: List<FilterRule> = emptyList(), urls: AttachmentUrls = DefaultAttachmentUrls,
  currentScope: String? = null): TopicContext {
  val candidates = if (selectedPid == null) {
    first.floors.map { first to it } + first.hotReplies.map { first to it } +
      current.floors.map { current to it }
  } else {
    first.floors.filter { it.lou == 0L }.map { first to it } +
      (current.floors + current.hotReplies).filter { it.pid == selectedPid }.map { current to it }
  }
  val anonymousNames = (first.users.values + current.users.values).filter { it.anonymous }
  fun anonymize(text: String): String = anonymousNames.fold(text.replace(Regex("#anony_[0-9a-f]{32}"), "匿名用户")) { body, anonymous ->
    if (anonymous.name.isNotBlank()) body.replace(anonymous.name, "匿名用户") else body
  }
  val sources = mutableListOf<AiSource>()
  val floorSources = mutableMapOf<Pair<Long, Long>, AiSource>()
  val seen = mutableSetOf<Triple<Long, Long, String>>()
  var blocked = 0
  fun add(detail: TopicDetail, floor: Floor, parent: Floor? = null, noteIndex: Int = 0) {
    val coordinate = parent ?: floor
    val part = if (parent == null) "floor" else "note:$noteIndex:${floor.pid}"
    if (!seen.add(Triple(detail.tid, coordinate.pid, part))) return
    val user = detail.users[floor.authorKey]
    val author = if (user?.anonymous == true || floor.authorId < 0) "匿名用户" else user?.name ?: "未知用户"
    if (matchFilterRules(rules, FilterSubject(author = user?.name, authorId = user?.uid,
        title = floor.subject, content = floor.content)) != null) { blocked++; return }
    val options = AttachmentUrlOptions(detail.attachBase, floor.postedAt)
    fun flatten(nodes: List<BBCodeNode>): String = nodes.joinToString("") { node ->
      when (node) {
        is TextNode -> node.value
        is CodeNode -> node.value
        LineBreakNode, DividerNode -> "\n"
        is ImageNode -> "[图片]"
        is AttachmentRef -> "[附件链接：${urls.attachmentUrl(node, options)}；未读取附件内容]"
        is LinkNode -> flatten(node.children) + " (${node.href})"
        is FloorRefNode -> "[引用楼层 ${node.pid}]" + flatten(node.children)
        is MentionNode -> node.username
        is AlbumNode -> albumImageUrls(node.value, options, urls).joinToString("\n") { "[图片]" }
        is QuoteNode -> "[引用他人，非本人立场]" + flatten(node.children) + "[引用结束]"
        is ChildBearing -> flatten(node.children)
        else -> childNodeLists(node).joinToString("\n") { flatten(it) }
      }
    }
    val nodes = parseBBCode(floor.content)
    val text = flatten(nodes) + floor.attachments.joinToString("") {
      if (it.kind == ATTACHMENT_IMAGE_KIND) "\n[图片]"
      else "\n[附件：${it.name.orEmpty()} ${it.url}；未读取附件内容]"
    }
    sources += AiSource("s${sources.size + 1}", detail.tid, coordinate.pid, coordinate.lou, detail.page,
      author, floor.postedAtText, (if (parent == null) "" else "[贴条] ") + anonymize(text),
      floorImageUrls(nodes, floor.attachments, options, urls), if (parent == null) "floor" else noteSourcePart(floor))
    if (parent == null) floorSources[detail.tid to floor.pid] = sources.last()
    floor.notes.forEachIndexed { index, note -> add(detail, note, coordinate, index) }
  }
  candidates.forEach { (detail, floor) -> add(detail, floor) }
  val image = if (selectedPid != null) sources.firstOrNull { it.pid == selectedPid }?.images?.firstOrNull()
    else sources.firstOrNull { it.floor == 0L }?.images?.firstOrNull()
      ?: current.floors.firstNotNullOfOrNull { floor ->
        floorSources[current.tid to floor.pid]?.images?.firstOrNull()
      }
  return TopicContext(sources, image, blocked, listOf(first.page, current.page).distinct(), title = if (sources.any { it.floor == 0L }) anonymize(first.subject) else "主楼未计入", currentScope = currentScope)
}

fun noteSourcePart(note: Floor): String {
  if (note.pid > 0) return "note:pid:${note.pid}"
  // 无 pid 的贴条没有稳定序号；正文指纹避免删除或重排后误指向另一条。
  val fields = listOf(note.authorId.toString(), note.postedAt.toString(), note.postedAtText,
    note.subject.orEmpty(), note.content) + note.attachments.map { it.url }
  val value = fields.joinToString("") { "${it.length}:$it" }
  return "note:sha256:" + java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
