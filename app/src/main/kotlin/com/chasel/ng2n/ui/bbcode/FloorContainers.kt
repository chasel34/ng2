package com.chasel.ng2n.ui.bbcode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.collections.immutable.ImmutableList

@Composable
fun SignatureBlock(
  model: FloorRenderModel,
  modifier: Modifier = Modifier,
  callbacks: BBCodeCallbacks = BBCodeCallbacks(),
) {
  if (model.isEmpty) return
  val colors = LocalNg2nColors.current
  Column(modifier = modifier.padding(top = 10.dp).fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(colors.divider),
    )
    Box(modifier = Modifier.padding(top = Spacing.sm)) {
      BBCodeContent(model = model, callbacks = callbacks)
    }
  }
}

fun signatureRenderOptions(base: BBCodeRenderOptions): BBCodeRenderOptions = base.copy(
  bodyFontSize = Typo.quoteBody.size.value,
  bodyLineHeight = Typo.quoteBody.lineHeight.value / Typo.quoteBody.size.value,
  colors = base.colors.copy(fg = base.colors.meta),
)

@Immutable
data class CommentEntry(
  val id: String,
  val author: String,
  val text: String,
)

@Composable
fun CommentStrip(
  comments: ImmutableList<CommentEntry>,
  modifier: Modifier = Modifier,
) {
  if (comments.isEmpty()) return
  val colors = LocalNg2nColors.current

  Column(
    modifier = modifier
      .padding(bottom = Spacing.md)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.surface2)
      .padding(horizontal = Spacing.md, vertical = 10.dp),
  ) {
    for (comment in comments) {
      Text(
        text = buildAnnotatedString {
          withStyle(SpanStyle(color = colors.link, fontWeight = FontWeight.Bold)) {
            append(comment.author)
          }
          append("：")
          append(comment.text)
        },
        fontSize = Typo.note.size,
        lineHeight = Typo.note.lineHeight,
        color = colors.fg2,
        modifier = Modifier.padding(vertical = 2.dp),
      )
    }
  }
}

@Composable
fun HotRepliesSection(
  count: Int,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  if (count <= 0) return
  val colors = LocalNg2nColors.current
  var open by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(colors.surface2),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { open = !open }
        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FireIcon(tint = colors.accent)
      Text(
        text = "热门回复($count)",
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = colors.fg2,
        modifier = Modifier.weight(1f),
      )
      ChevronIcon(tint = colors.meta, expanded = open)
    }
    AnimatedVisibility(
      visible = open,
      enter = expandVertically(tween(HOT_REPLIES_MS)) + fadeIn(tween(HOT_REPLIES_MS)),
      exit = shrinkVertically(tween(HOT_REPLIES_MS)) + fadeOut(tween(HOT_REPLIES_MS)),
    ) {
      Column(Modifier.fillMaxWidth()) { content() }
    }
  }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(colors.divider),
  )
}

private const val HOT_REPLIES_MS = 200
