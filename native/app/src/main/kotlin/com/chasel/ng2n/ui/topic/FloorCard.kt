package com.chasel.ng2n.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.chasel.ng2n.ui.theme.AVATAR_BASE_SIZE
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 一个楼层卡片(设计稿 isArticle 的 `floors` 行)。
 *
 * **滚动路径零计算**:这里出现的每个值都来自 [FloorRenderItem](后台建好的成品),
 * 没有解析、没有拼串、没有 URL 计算。回调经 [FloorActions] 一次性传入,
 * 引用在整屏生命周期内不变 —— 菜单开合、对话框弹出不会让屏上的卡片重画。
 */
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
)

@Composable
fun FloorCard(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  chainDepth: Int,
  actions: FloorActions,
  showSignature: Boolean,
  imagesUnlocked: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = LocalNg2nColors.current

  // 回调都只依赖 floor 与 actions,两者在这张卡的生命周期内不变
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
      // 正文段自己吃 down 事件,长按得从渲染器转发上来,否则「长按整卡出菜单」
      // 只在正文以外的空白处才灵(见 BBCodeCallbacks.onLongPress)
      onLongPress = { actions.onOpenMenu(floor) },
    )
  }

  Column(
    // 长按整卡也能出楼层菜单(RN 侧「长按或菜单钮」)
    modifier = modifier
      .fillMaxWidth()
      .combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = { actions.onOpenMenu(floor) },
        onClick = { },
      )
      // 设计稿:楼层内距 14/16/6,底部一条 divider
      .padding(top = Spacing.row, start = Spacing.lg, end = Spacing.lg, bottom = 6.dp),
  ) {
    FloorHeader(floor = floor, onOpenProfile = { actions.onOpenProfile(floor) })

    // 回复楼层也可以自带标题;主楼的标题就是主题标题,顶栏已经有了
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

    // 签名档:附在正文后面的一小块,压一档字号 + 上面一条分隔线
    if (showSignature) floor.signature?.let { SignatureBlock(model = it) }

    // 投票是楼层字段不是 BBCode(API 文档 §3),所以画在正文之后而不是渲染器里
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

    FloorActionRow(floor = floor, mark = mark, actions = actions)

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
            // 用户状态标注(功能文档 §2.3):楼主 / 匿名 / 禁言 / 已封禁
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
            // alterinfo 非空 = 被编辑过(API 文档 §3);编辑记录本身不展开
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

/**
 * 楼层头像。没设头像、或者图挂了,一律回落到「纯色圆底 + 名字首字」。
 * 边长由「字体和头像大小」设置定 —— 那一档归票 17,这里先用基准值。
 */
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

/**
 * 赞踩与楼层菜单。回复是 v1 排除项(spec §一.2),入口保留占位。
 * 赞数 = 服务端 score + 本会话增量;已赞时图标与数字染主题色(设计稿 f.likeColor)。
 */
@Composable
private fun FloorActionRow(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  actions: FloorActions,
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
    // 点赞钮为了把图标和赞数排进同一个圆角背景,没走 [IconAction],语义得自己挂:
    // 不挂的话无障碍树里是个 text 与 content-desc 都为空的可点节点(票 26),
    // TalkBack 只念得出里面那个赞数。**不合并子节点** —— 赞数那条 Text 仍要单独读得到。
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
    // 设计稿把这一排最右边的「更多」收窄 4pt,让它贴着卡片右缘
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

/**
 * 附件宫格。默认折叠成设计稿那条「点击显示附件(N)」,展开后是三列方格。
 *
 * 默认折叠不只是照设计稿:附件常常是几张几 MB 的原图,一进帖子全量拉图既费流量又慢。
 * 「仅 Wi-Fi 下加载图片」关掉自动展开的那条路 —— 折叠条上多一句「移动网络」,
 * 点了照样能看。
 */
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

  if (!open) {
    Row(
      modifier = Modifier
        .padding(top = Spacing.md)
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
    return
  }

  Column(Modifier.padding(top = Spacing.md).fillMaxWidth()) {
    // 三列方格,格间距 6(设计稿)。只有图片进宫格 —— 压缩包、种子当图片渲染
    // 就是一格加载失败,所以另起一行按「文件名 · 大小」列出来
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
              // 宫格里用缩略图,点开大图才拉原图
              model = attachment.thumbnailUrl ?: attachment.url,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
          }
        }
        // 最后一行不足三格时补空,免得那一两张被拉宽
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

/** 设计稿:附件宫格三列、格间距 6、方格圆角 10。 */
private const val ATTACH_COLUMNS = 3
private val ATTACH_GAP = 6.dp

/** 服务端给的 `size` 单位是 KB。 */
private fun formatSize(sizeKb: Long): String =
  if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "$sizeKb KB"

/** 热门回复区里的卡片与正文楼层同一个组件,只是底色压一档。 */
@Composable
fun HotReplyCard(
  floor: FloorRenderItem,
  mark: RecommendMark?,
  chainDepth: Int,
  actions: FloorActions,
  showSignature: Boolean,
  imagesUnlocked: Boolean,
) {
  Box(Modifier.background(Color.Transparent)) {
    FloorCard(
      floor = floor,
      mark = mark,
      chainDepth = chainDepth,
      actions = actions,
      showSignature = showSignature,
      imagesUnlocked = imagesUnlocked,
    )
  }
}
