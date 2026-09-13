package com.chasel.ng2n.ui.topic

import androidx.compose.ui.graphics.Color
import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.api.Floor
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.core.local.decodeAnonymousName
import com.chasel.ng2n.core.local.extractQuoteRefs
import com.chasel.ng2n.core.local.formatReputation
import com.chasel.ng2n.core.local.parseVote
import com.chasel.ng2n.ui.bbcode.ATTACHMENT_IMAGE_KIND
import com.chasel.ng2n.ui.bbcode.BBCodeNodeShape
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.CommentEntry
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.bbcode.collectFloorImages
import com.chasel.ng2n.ui.bbcode.plainTextOf
import com.chasel.ng2n.ui.bbcode.resolveFloorDice
import com.chasel.ng2n.ui.bbcode.signatureRenderOptions
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.ImmutableMap

object TopicPageBuilder {

  fun build(
    detail: TopicDetail,
    tid: Long,
    style: TopicRenderStyle,
    urls: AttachmentUrls = DefaultAttachmentUrls,
    replySources: List<TopicDetail> = emptyList(),
  ): PageRenderModel {
    val starter = detail.floors.firstOrNull { it.isStarter }
    val previews = buildReplyPreviews(detail, replySources, style, urls)
    return PageRenderModel(
      page = detail.page,
      subject = detail.subject,
      boardName = detail.boardName,
      totalRows = detail.totalRows,
      rowsPerPage = detail.rowsPerPage,
      totalPages = detail.totalPages,
      attachBase = detail.attachBase,
      source = detail.source,
      floors = detail.floors.map { buildFloor(it, detail, tid, style, urls, previews) }.toImmutableList(),
      hotReplies = detail.hotReplies.map { buildFloor(it, detail, tid, style, urls, previews) }
        .toImmutableList(),
      starterName = starter?.let { detail.users[it.authorKey]?.name },
    )
  }

  private fun buildFloor(
    floor: Floor,
    detail: TopicDetail,
    tid: Long,
    style: TopicRenderStyle,
    urls: AttachmentUrls,
    previews: ImmutableMap<QuoteRef, FloorRenderModel>,
  ): FloorRenderItem {
    val user = detail.users[floor.authorKey]
    val nodes = parseBBCode(floor.content)
    val dice = resolveFloorDice(
      nodes,
      DiceSeed(authorId = floor.authorId, tid = tid, pid = floor.pid),
    )
    val options = BBCodeRenderOptions(
      attachBase = detail.attachBase,
      postedAt = floor.postedAt,
      dice = dice.toImmutableList(),
      colors = style.colors,
      bodyFontSize = style.bodyFontSize,
      bodyLineHeight = style.bodyLineHeight,
      attachmentUrls = urls,
    )

    val signature = user?.signature
      ?.takeIf { style.showSignature && it.isNotEmpty() }
      ?.let { RenderModelBuilder.build(parseBBCode(it), signatureRenderOptions(options)) }
      ?.takeUnless { it.isEmpty }

    val attachOptions = AttachmentUrlOptions(base = detail.attachBase, postedAt = floor.postedAt)
    val anonymousName = user?.rawName?.let(::decodeAnonymousName)
    val name = user?.name ?: "未知用户"

    return FloorRenderItem(
      pid = floor.pid,
      lou = floor.lou,
      recommendPid = if (floor.lou == 0L) 0L else floor.pid,
      authorKey = floor.authorKey,
      user = user,
      displayName = name,
      nameColor = anonymousName?.colors?.first?.let(::parseHexColor),
      isStarter = floor.isStarter,
      anonymous = user?.anonymous == true,
      muted = user?.muted == true,
      nuked = user?.nuked == true,
      avatarUrl = user?.avatarUrl,
      avatarColor = avatarColorFor(user?.key ?: name),
      avatarInitial = initialOf(name),
      levelText = user?.level ?: "—",
      reputationText = formatReputation(user?.reputation ?: 0.0),
      postCount = user?.postCount ?: 0L,
      postedAtText = floor.postedAtText,
      edited = floor.edited,
      client = floor.client,
      score = floor.score,
      subject = floor.subject?.takeIf { floor.lou > 0L },
      content = floor.content,
      body = RenderModelBuilder.build(nodes, options.copy(replyPreviews = previews)),
      signature = signature,
      comments = floor.notes.map { note -> commentEntryOf(note, detail.users) }.toImmutableList(),
      vote = floor.vote?.let { parseVote(it, tid) },
      attachmentImages = floor.attachments.filter { it.kind == ATTACHMENT_IMAGE_KIND }
        .toImmutableList(),
      attachmentFiles = floor.attachments.filterNot { it.kind == ATTACHMENT_IMAGE_KIND }
        .toImmutableList(),
      images = collectFloorImages(nodes, floor.attachments, attachOptions, urls).toImmutableList(),
      quoteRefs = extractQuoteRefs(nodes, BBCodeNodeShape).toImmutableList(),
      profileUid = user?.uid?.takeIf { user.anonymous.not() },
    )
  }

  private fun commentEntryOf(note: Floor, users: Map<String, FloorUser>): CommentEntry =
    CommentEntry(
      id = note.pid.toString(),
      author = users[note.authorKey]?.name ?: "匿名",
      text = plainTextOf(note.content),
    )
}

fun avatarColorFor(key: String): Color {
  var hash = 0L
  for (char in key) hash = (hash * 31 + char.code) % 0xFFFFFF
  return AVATAR_COLORS[(hash % AVATAR_COLORS.size).toInt()]
}

private val AVATAR_COLORS = listOf(
  Color(0xFF3E6B7E),
  Color(0xFF7E5A3E),
  Color(0xFF5A6E3E),
  Color(0xFF6E3E5A),
  Color(0xFF3E5A7E),
  Color(0xFF7E6B3E),
  Color(0xFF4A4A6E),
)

fun initialOf(name: String): String {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return "#"
  val first = trimmed.codePointAt(0)
  return String(Character.toChars(first))
}

private fun parseHexColor(hex: String): Color? {
  val value = hex.toLongOrNull(16) ?: return null
  return Color(0xFF000000L or (value and 0xFFFFFFL))
}
