package com.chasel.ng2n.ui.topic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.api.FloorClient
import com.chasel.ng2n.core.api.RecommendAction
import com.chasel.ng2n.core.api.RecommendMark
import com.chasel.ng2n.core.api.RecommendState
import com.chasel.ng2n.ui.bbcode.BBCodeCallbacks
import com.chasel.ng2n.ui.bbcode.BBCodeContent
import com.chasel.ng2n.ui.bbcode.CommentStrip
import com.chasel.ng2n.ui.bbcode.SignatureBlock
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.theme.AVATAR_BASE_SIZE
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

@Immutable
data class FloorActions(
  val onOpenImage: (FloorRenderItem, String) -> Unit,
  val onRecommend: (FloorRenderItem, RecommendAction) -> Unit,
  val onOpenMenu: (FloorRenderItem) -> Unit,
  val onOpenProfile: (FloorRenderItem) -> Unit,
  val onOpenChain: (FloorRenderItem) -> Unit,
  val onOpenLink: (String) -> Unit,
  val onOpenUser: (String) -> Unit,
  val onOpenTopic: (String) -> Unit,
  val onOpenFloorRef: (String) -> Unit,
  val onNotAvailable: () -> Unit,
  val onEditBookmark: (FloorRenderItem) -> Unit,
)

@Immutable
data class FloorBookmarkMark(val note: String?)

@Composable
fun FloorCard(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  chainDepth: Int,
  actions: FloorActions,
  showSignature: Boolean,
  imagesUnlocked: Boolean,
  modifier: Modifier = Modifier,
  bookmark: FloorBookmarkMark? = null,
) {
  val colors = LocalNg2nColors.current

  val callbacks = remember(floor, actions, chainDepth) {
    BBCodeCallbacks(
      onOpenLink = actions.onOpenLink,
      onOpenUser = actions.onOpenUser,
      onOpenTopic = actions.onOpenTopic,
      onOpenFloor = actions.onOpenFloorRef,
      onOpenMention = { },
      onOpenImage = { url -> actions.onOpenImage(floor, url) },
      onOpenExternal = actions.onOpenLink,
      onOpenChain = { actions.onOpenChain(floor) },
      chainDepth = chainDepth,
      onLongPress = { actions.onOpenMenu(floor) },
    )
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = { actions.onOpenMenu(floor) },
        onClick = { },
      )
      .padding(top = Spacing.row, start = Spacing.lg, end = Spacing.lg, bottom = 6.dp),
  ) {
    FloorHeader(floor = floor, onOpenProfile = { actions.onOpenProfile(floor) })

    floor.subject?.let {
      Text(
        text = it,
        fontSize = Typo.section.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.fg,
        modifier = Modifier.padding(top = 11.dp),
      )
    }

    Box(Modifier.padding(top = 11.dp)) {
      BBCodeContent(model = floor.body, callbacks = callbacks)
    }

    if (showSignature) floor.signature?.let { SignatureBlock(model = it) }

    floor.vote?.let { VoteBlock(vote = it, onNotAvailable = actions.onNotAvailable) }

    if (floor.attachmentCount > 0) {
      AttachmentGrid(
        images = floor.attachmentImages,
        files = floor.attachmentFiles,
        unlocked = imagesUnlocked,
        onOpenImage = { url -> actions.onOpenImage(floor, url) },
        onOpenFile = actions.onOpenLink,
      )
    }

    FloorActionRow(floor = floor, mark = mark, actions = actions, bookmark = bookmark)

    if (floor.comments.isNotEmpty()) CommentStrip(comments = floor.comments)
  }
  Divider()
}

@Composable
private fun FloorHeader(floor: FloorRenderItem, onOpenProfile: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
    Avatar(floor = floor, onClick = onOpenProfile)
    Column(Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Text(
          text = buildAnnotatedString {
            withStyle(SpanStyle(color = floor.nameColor ?: colors.primary)) {
              append(floor.displayName)
            }
            if (floor.isStarter) {
              withStyle(SpanStyle(color = colors.accent)) { append("(楼主)") }
            }
            if (floor.anonymous) withStyle(SpanStyle(color = colors.meta)) { append("(匿名)") }
            if (floor.muted) withStyle(SpanStyle(color = colors.danger)) { append("(禁言)") }
            if (floor.nuked) withStyle(SpanStyle(color = colors.danger)) { append("(已封禁)") }
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          fontSize = Typo.notice.size,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f, fill = false).clickable(onClick = onOpenProfile),
        )
        Text(
          text = buildAnnotatedString {
            append(floor.postedAtText)
            if (floor.edited) withStyle(SpanStyle(color = colors.meta)) { append(" · 已编辑") }
          },
          fontSize = Typo.meta.size,
          color = colors.meta,
        )
      }
      Row(
        modifier = Modifier.padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        MetaText("级别: ${floor.levelText}")
        MetaText("威望: ${floor.reputationText}")
        MetaText("发帖: ${floor.postCount}")
        Row(
          modifier = Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ClientIcon(tint = colors.meta, kind = floor.client.toIconKind())
          MetaText("[${floor.lou} 楼]")
        }
      }
    }
  }
}

private fun FloorClient.toIconKind(): ClientIconKind = when (this) {
  FloorClient.ANDROID -> ClientIconKind.ANDROID
  FloorClient.IOS -> ClientIconKind.IOS
  FloorClient.OTHER -> ClientIconKind.OTHER
}

@Composable
private fun MetaText(text: String) {
  Text(text, fontSize = Typo.meta.size, color = LocalNg2nColors.current.meta)
}

@Composable
private fun Avatar(floor: FloorRenderItem, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  var failed by remember(floor.avatarUrl) { mutableStateOf(false) }
  val size = AVATAR_BASE_SIZE.dp
  val shape = CircleShape

  Box(
    modifier = Modifier
      .size(size)
      .clip(shape)
      .background(floor.avatarColor)
      .clickable(enabled = floor.profileUid != null, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    val url = floor.avatarUrl
    if (url == null || failed) {
      Text(
        text = floor.avatarInitial,
        color = colors.onPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = Typo.notice.size,
      )
    } else {
      AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = Modifier.size(size),
      )
    }
  }
}

@Composable
private fun FloorActionRow(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  actions: FloorActions,
  bookmark: FloorBookmarkMark?,
) {
  val colors = LocalNg2nColors.current
  val liked = mark?.state == RecommendState.LIKED
  val disliked = mark?.state == RecommendState.DISLIKED
  val likeColor = if (liked) colors.primary else colors.meta

  Row(
    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (bookmark != null) {
      Row(
        modifier = Modifier
          .height(28.dp)
          .widthIn(max = BOOKMARK_CHIP_MAX_WIDTH)
          .clip(RoundedCornerShape(Radius.xs))
          .background(colors.primaryContainer)
          .clickable { actions.onEditBookmark(floor) }
          .semantics { contentDescription = "编辑书签" }
          .padding(start = Spacing.sm, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        BookmarkAddedIcon(tint = colors.primary, size = 14.dp)
        Text(
          text = bookmark.note ?: "书签",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          fontSize = Typo.listMeta.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.primary,
        )
      }
      Spacer(Modifier.weight(1f))
    }
    Row(
      modifier = Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(10.dp))
        .clickable { actions.onRecommend(floor, RecommendAction.LIKE) }
        .semantics { contentDescription = "点赞" }
        .padding(horizontal = Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      ThumbUpIcon(tint = likeColor)
      Text(
        text = (floor.score + (mark?.scoreDelta ?: 0L)).toString(),
        fontSize = Typo.listMeta.size,
        color = likeColor,
      )
    }
    IconAction(label = "点踩", width = 38.dp) {
      Box(
        Modifier.clickable { actions.onRecommend(floor, RecommendAction.DISLIKE) },
        contentAlignment = Alignment.Center,
      ) { ThumbDownIcon(tint = if (disliked) colors.primary else colors.meta) }
    }
    IconAction(label = "回复", width = 38.dp) {
      Box(Modifier.clickable(onClick = actions.onNotAvailable), contentAlignment = Alignment.Center) {
        ReplyIcon(tint = colors.meta)
      }
    }
    IconAction(label = "楼层菜单", width = 34.dp) {
      Box(
        Modifier.clickable { actions.onOpenMenu(floor) },
        contentAlignment = Alignment.Center,
      ) { OverflowIcon(tint = colors.meta, size = 19.dp) }
    }
  }
}

@Composable
private fun IconAction(label: String, width: Dp, content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .width(width)
      .height(40.dp)
      .clip(RoundedCornerShape(10.dp))
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) { content() }
}

@Composable
private fun AttachmentGrid(
  images: List<FloorAttachment>,
  files: List<FloorAttachment>,
  unlocked: Boolean,
  onOpenImage: (String) -> Unit,
  onOpenFile: (String) -> Unit,
) {
  val colors = LocalNg2nColors.current
  var open by remember { mutableStateOf(false) }
  val count = images.size + files.size

  Column(Modifier.padding(top = Spacing.md).fillMaxWidth()) {
    AnimatedVisibility(visible = !open, enter = ATTACH_ENTER, exit = ATTACH_EXIT) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(42.dp)
          .clip(RoundedCornerShape(Radius.md))
          .background(colors.surface2)
          .clickable { open = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
      ) {
        if (unlocked) AttachImageIcon(tint = colors.fg2) else CellularIcon(tint = colors.fg2)
        Text(
          text = if (unlocked) "点击显示附件($count)" else "移动网络 · 点击显示附件($count)",
          fontSize = Typo.notice.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg2,
        )
      }
    }

    AnimatedVisibility(visible = open, enter = ATTACH_ENTER, exit = ATTACH_EXIT) {
      Column(Modifier.fillMaxWidth()) {
        images.chunked(ATTACH_COLUMNS).forEach { row ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = ATTACH_GAP),
            horizontalArrangement = Arrangement.spacedBy(ATTACH_GAP),
          ) {
            row.forEach { attachment ->
              Box(
                modifier = Modifier
                  .weight(1f)
                  .aspectRatio(1f)
                  .clip(RoundedCornerShape(10.dp))
                  .background(colors.surface2)
                  .clickable { onOpenImage(attachment.url) },
              ) {
                AsyncImage(
                  model = attachment.thumbnailUrl ?: attachment.url,
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
              }
            }
            repeat(ATTACH_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
          }
        }

        files.forEach { attachment ->
          Row(
            modifier = Modifier
              .padding(top = 7.dp)
              .fillMaxWidth()
              .clip(RoundedCornerShape(Radius.md))
              .background(colors.surface2)
              .clickable { onOpenFile(attachment.url) }
              .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
          ) {
            DownloadIcon(tint = colors.link, size = 16.dp)
            Text(
              text = attachment.name ?: attachment.url,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              fontSize = Typo.listMeta.size,
              color = colors.link,
              modifier = Modifier.weight(1f),
            )
            attachment.sizeKb?.let {
              Text(formatSize(it), fontSize = Typo.meta.size, color = colors.meta)
            }
          }
        }

        Text(
          text = "收起附件",
          fontSize = Typo.listMeta.size,
          color = colors.meta,
          modifier = Modifier
            .padding(top = 7.dp)
            .fillMaxWidth()
            .clickable { open = false },
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

private val ATTACH_ENTER: EnterTransition =
  expandVertically(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)) +
    fadeIn(tween(Motion.DURATION_BASE, easing = Motion.easeStandard))

private val ATTACH_EXIT: ExitTransition =
  shrinkVertically(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)) +
    fadeOut(tween(Motion.DURATION_BASE, easing = Motion.easeStandard))

private const val ATTACH_COLUMNS = 3
private val ATTACH_GAP = 6.dp
private val BOOKMARK_CHIP_MAX_WIDTH = 200.dp

private fun formatSize(sizeKb: Long): String =
  if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "$sizeKb KB"

@Composable
fun HotReplyCard(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  chainDepth: Int,
  actions: FloorActions,
  showSignature: Boolean,
  imagesUnlocked: Boolean,
  bookmark: FloorBookmarkMark? = null,
) {
  Box(Modifier.background(Color.Transparent)) {
    FloorCard(
      floor = floor,
      mark = mark,
      chainDepth = chainDepth,
      actions = actions,
      showSignature = showSignature,
      imagesUnlocked = imagesUnlocked,
      bookmark = bookmark,
    )
  }
}
