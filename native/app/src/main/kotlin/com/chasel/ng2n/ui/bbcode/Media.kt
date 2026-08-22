package com.chasel.ng2n.ui.bbcode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.image.PostImage
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 媒体与特殊标签的卡片:`[dice]` `[flash]` `[attach]` `[album]`
 * (RN 侧原件 `src/ui/bbcode/media.tsx`)。
 *
 * `[flash]` 不内联播放(ADR-0001):与其做一个半残的内置播放器,不如给一张卡片、
 * 点了交给系统——手机上装了播放器的体验比任何内置方案都好,没装的话浏览器兜底。
 */

/** `[dice]` 的结果卡。排版照网页版那张小表:`ROLL : 表达式 = 展开 = 合计`。 */
@Composable
internal fun DiceCard(segment: DiceSegment) {
  val colors = LocalNg2nColors.current
  val outcome = segment.outcome

  // 点数要靠楼层的 authorId/tid/pid 才算得出来,调用方没给就退回显示表达式
  if (outcome == null) {
    Text(
      text = "[骰子 ${segment.expression}]",
      fontSize = Typo.body.size,
      lineHeight = Typo.body.lineHeight,
      color = colors.meta,
    )
    return
  }

  val expanded = formatDiceTerms(outcome.terms)
  val line: AnnotatedString = buildAnnotatedString {
    append(outcome.expression)
    if (expanded.isNotEmpty()) {
      withStyle(SpanStyle(color = colors.meta)) { append(" = $expanded") }
    }
    if (outcome.sum == null) {
      // 网页版此时显示 OUT OF LIMIT / ERROR:一次最多 10 颗、面数最多 100000
      withStyle(SpanStyle(color = colors.danger)) { append(" = 超出骰子上限") }
    } else {
      withStyle(SpanStyle(color = colors.fg, fontWeight = FontWeight.Bold)) {
        append(" = ${outcome.sum}")
      }
    }
  }

  Row(
    modifier = Modifier
      .padding(top = BLOCK_GAP)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.quote)
      .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "ROLL",
      fontSize = Typo.caption.size,
      fontWeight = FontWeight.Bold,
      color = colors.accent,
    )
    Text(
      text = line,
      fontSize = Typo.note.size,
      lineHeight = Typo.note.lineHeight,
      color = colors.fg2,
      modifier = Modifier.weight(1f),
    )
  }
}

/** `[flash]` / `[flash=video]` / `[flash=audio]`:点了交给系统播放器或浏览器。 */
@Composable
internal fun MediaCard(segment: MediaSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val onOpen = callbacks.onOpenExternal

  Row(
    modifier = Modifier
      .padding(top = BLOCK_GAP)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.surface2)
      .then(if (onOpen == null) Modifier else Modifier.clickable { onOpen(segment.url) })
      .padding(Spacing.md),
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(38.dp)
        .clip(CircleShape)
        .background(colors.primary),
      contentAlignment = Alignment.Center,
    ) {
      PlayIcon(tint = colors.onPrimary)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = segment.label,
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = colors.fg,
      )
      Text(
        text = segment.fileName,
        fontSize = Typo.listMeta.size,
        color = colors.meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    ExternalIcon(tint = colors.meta)
  }
}

/** `[attach]`:附件本体,和楼层附件区一样点了外跳下载。 */
@Composable
internal fun AttachCard(segment: AttachSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val onOpen = callbacks.onOpenExternal

  Row(
    modifier = Modifier
      .padding(top = 7.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.surface2)
      .then(if (onOpen == null) Modifier else Modifier.clickable { onOpen(segment.url) })
      .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DownloadIcon(tint = colors.link)
    Text(
      text = segment.fileName,
      fontSize = Typo.listMeta.size,
      color = colors.link,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * `[album]`:默认收起成一条「共 N 张」,展开后按正文图片的样式竖排。
 *
 * 相册动辄十几张原图,一进楼就全拉等于把流量烧光——和附件宫格同一个理由。
 */
@Composable
internal fun AlbumCard(segment: AlbumSegment, callbacks: BBCodeCallbacks) {
  if (segment.images.isEmpty()) return

  CollapsibleCard(
    title = "相册 · 共 ${segment.images.size} 张图片",
    openLabel = "点击查看",
    icon = { tint -> ImageIcon(tint) },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      for (image in segment.images) {
        PostImage(
          url = image.url,
          thumbnailUrl = image.thumbnailUrl,
          onClick = callbacks.onOpenImage,
        )
      }
    }
  }
}
