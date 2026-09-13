package com.chasel.ng2n.ui.bbcode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.bbcode.BoxVariant
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

@Composable
internal fun QuoteCard(segment: QuoteSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val chain = segment.chain
  val onOpenChain = callbacks.onOpenChain

  Column(
    modifier = Modifier
      .padding(top = BLOCK_GAP)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.quote)
      .drawLeftRail(colors.track)
      .padding(start = 13.dp + QUOTE_RAIL, top = 11.dp, end = 13.dp, bottom = 11.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    BBCodeContent(model = segment.body, callbacks = callbacks)

    if (segment.replyHeader) {
      val preview = segment.preview
      if (preview != null && !preview.isEmpty) {
        BBCodeContent(model = preview, callbacks = callbacks.copy(onOpenChain = null))
      } else if (chain != null) {
        Text("点击 Reply 查看原文", fontSize = Typo.listMeta.size, color = colors.meta)
      }
    }

    if (chain != null && onOpenChain != null && callbacks.chainDepth >= MIN_CHAIN_DEPTH) {
      Row(
        modifier = Modifier
          .padding(top = 2.dp)
          .clickable { onOpenChain(chain) },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ChainIcon(tint = colors.primary)
        Text(
          text = "查看对话链(${callbacks.chainDepth} 层)",
          fontSize = Typo.listMeta.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.primary,
        )
      }
    }
  }
}

private val QUOTE_RAIL = 3.dp

private const val MIN_CHAIN_DEPTH = 2

private fun Modifier.drawLeftRail(color: Color): Modifier = drawBehind {
  drawRect(color = color, size = Size(QUOTE_RAIL.toPx(), size.height))
}

private fun Modifier.bottomDivider(color: Color): Modifier = drawBehind {
  val thickness = 1.dp.toPx()
  drawRect(
    color = color,
    topLeft = Offset(0f, size.height - thickness),
    size = Size(size.width, thickness),
  )
}

private fun Modifier.rightBottomDivider(color: Color): Modifier = drawBehind {
  val thickness = 1.dp.toPx()
  drawRect(
    color = color,
    topLeft = Offset(size.width - thickness, 0f),
    size = Size(thickness, size.height),
  )
  drawRect(
    color = color,
    topLeft = Offset(0f, size.height - thickness),
    size = Size(size.width, thickness),
  )
}

@Composable
internal fun HeadingBlock(segment: HeadingSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .padding(top = 11.dp)
      .fillMaxWidth()
      .bottomDivider(colors.divider)
      .padding(bottom = Spacing.xs),
  ) {
    BBCodeContent(model = segment.body, callbacks = callbacks)
  }
}

@Composable
internal fun DividerBlock() {
  val colors = LocalNg2nColors.current
  Box(
    modifier = Modifier
      .padding(vertical = Spacing.md)
      .fillMaxWidth()
      .height(1.dp)
      .background(colors.divider),
  )
}

@Composable
internal fun ListBlock(segment: ListSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .padding(top = Spacing.xs)
      .fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    segment.items.forEachIndexed { index, item ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Text(
          text = if (segment.ordered) "${index + 1}." else "·",
          fontSize = Typo.body.size,
          lineHeight = Typo.body.lineHeight,
          color = colors.meta,
          modifier = Modifier.width(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
          BBCodeContent(model = item, callbacks = callbacks)
        }
      }
    }
  }
}

@Composable
internal fun TableBlock(segment: TableSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val guard = LocalHorizontalDragGuard.current
  val scroll = rememberScrollState()

  Box(
    modifier = Modifier
      .padding(top = BLOCK_GAP)
      .fillMaxWidth()
      .pointerInput(guard) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          guard.begin()
          try {
            waitForUpOrCancellation()
          } finally {
            guard.end()
          }
        }
      }
      .horizontalScroll(scroll),
  ) {
    Column(
      modifier = Modifier
        .clip(RoundedCornerShape(Radius.sm))
        .border(width = 1.dp, color = colors.divider, shape = RoundedCornerShape(Radius.sm)),
    ) {
      for (row in segment.rows) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
          for (cell in row.cells) {
            TableCell(width = cell.width, colors = colors) {
              BBCodeContent(model = cell.body, callbacks = callbacks)
            }
          }
          repeat(row.paddingCells) {
            TableCell(width = tableCellWidth(1), colors = colors) {}
          }
        }
      }
    }
  }
}

@Composable
private fun TableCell(
  width: Dp,
  colors: Ng2nColors,
  content: @Composable () -> Unit,
) {
  Box(
    modifier = Modifier
      .width(width)
      .fillMaxHeight()
      .rightBottomDivider(colors.divider)
      .padding(horizontal = Spacing.sm, vertical = 7.dp),
  ) {
    content()
  }
}

@Composable
internal fun BoxBlock(segment: BoxSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current

  if (segment.variant != BoxVariant.LESSERNUKE) {
    Column(
      modifier = Modifier
        .padding(top = Spacing.sm)
        .fillMaxWidth()
        .clip(RoundedCornerShape(Radius.md))
        .background(colors.surface2)
        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
      BBCodeContent(model = segment.body, callbacks = callbacks)
    }
    return
  }

  CollapsibleCard(
    title = segment.notice ?: "",
    openLabel = "点击查看",
    danger = true,
    icon = { tint -> WarningIcon(tint) },
  ) {
    BBCodeContent(model = segment.body, callbacks = callbacks)
  }
}

@Composable
internal fun CollapseBlock(segment: CollapseSegment, callbacks: BBCodeCallbacks) {
  CollapsibleCard(
    title = segment.title,
    openLabel = "点击展开",
    icon = { tint -> ArticleIcon(tint) },
  ) {
    BBCodeContent(model = segment.body, callbacks = callbacks)
  }
}

@Composable
internal fun CollapsibleCard(
  title: String,
  openLabel: String,
  modifier: Modifier = Modifier,
  danger: Boolean = false,
  icon: @Composable (Color) -> Unit,
  content: @Composable () -> Unit,
) {
  val colors = LocalNg2nColors.current
  var open by remember { mutableStateOf(false) }
  val accent = if (danger) colors.danger else colors.fg2
  val shape = RoundedCornerShape(Radius.md)

  Column(
    modifier = modifier
      .padding(top = BLOCK_GAP)
      .fillMaxWidth()
      .clip(shape)
      .then(
        if (danger) {
          Modifier.border(width = 1.dp, color = colors.danger, shape = shape)
        } else {
          Modifier.background(colors.surface2)
        },
      ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { open = !open }
        .heightIn(min = 42.dp)
        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon(accent)
      Text(
        text = title,
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = if (open) "收起" else openLabel,
        fontSize = Typo.listMeta.size,
        color = colors.meta,
      )
    }
    AnimatedVisibility(
      visible = open,
      enter = expandVertically(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)) + fadeIn(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)),
      exit = shrinkVertically(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)) + fadeOut(tween(Motion.DURATION_BASE, easing = Motion.easeStandard)),
    ) {
      Column(
        modifier = Modifier.padding(
          start = Spacing.md,
          end = Spacing.md,
          bottom = Spacing.md,
        ),
      ) {
        content()
      }
    }
  }
}


@Stable
interface HorizontalDragGuard {
  fun begin()

  fun end()
}

val LocalHorizontalDragGuard = staticCompositionLocalOf<HorizontalDragGuard> {
  object : HorizontalDragGuard {
    override fun begin() = Unit
    override fun end() = Unit
  }
}
