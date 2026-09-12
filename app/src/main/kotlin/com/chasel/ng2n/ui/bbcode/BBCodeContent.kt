package com.chasel.ng2n.ui.bbcode

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.chasel.ng2n.core.bbcode.Align
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.ui.image.PostImage
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalTextScale
import com.chasel.ng2n.ui.theme.Spacing

@Composable
fun BBCodeContent(
  model: FloorRenderModel,
  modifier: Modifier = Modifier,
  callbacks: BBCodeCallbacks = BBCodeCallbacks(),
) {
  Column(modifier = modifier.fillMaxWidth()) {
    for (segment in model.segments) {
      RenderSegmentView(segment = segment, callbacks = callbacks)
    }
  }
}

@Immutable
data class BBCodeCallbacks(
  val onOpenLink: ((String) -> Unit)? = null,
  val onOpenUser: ((String) -> Unit)? = null,
  val onOpenTopic: ((String) -> Unit)? = null,
  val onOpenFloor: ((String) -> Unit)? = null,
  val onOpenMention: ((String) -> Unit)? = null,
  val onOpenImage: ((String) -> Unit)? = null,
  val onOpenExternal: ((String) -> Unit)? = null,
  val onOpenChain: ((QuoteRef) -> Unit)? = null,
  val chainDepth: Int = 0,
  val onLongPress: (() -> Unit)? = null,
)

@Composable
private fun RenderSegmentView(segment: RenderSegment, callbacks: BBCodeCallbacks) {
  when (segment) {
    is TextSegment -> TextSegmentView(segment, callbacks)
    is QuoteSegment -> QuoteCard(segment, callbacks)
    is ImageSegment -> PostImage(
      url = segment.url,
      thumbnailUrl = segment.thumbnailUrl,
      onClick = callbacks.onOpenImage,
      modifier = Modifier.padding(top = IMAGE_GAP),
    )
    DividerSegment -> DividerBlock()
    is HeadingSegment -> HeadingBlock(segment, callbacks)
    is AlignSegment -> AlignBlock(segment, callbacks)
    is CollapseSegment -> CollapseBlock(segment, callbacks)
    is BoxSegment -> BoxBlock(segment, callbacks)
    is ListSegment -> ListBlock(segment, callbacks)
    is TableSegment -> TableBlock(segment, callbacks)
    is DiceSegment -> DiceCard(segment)
    is MediaSegment -> MediaCard(segment, callbacks)
    is AttachSegment -> AttachCard(segment, callbacks)
    is AlbumSegment -> AlbumCard(segment, callbacks)
    is GroupSegment -> Column(Modifier.fillMaxWidth()) {
      for (child in segment.body.segments) RenderSegmentView(child, callbacks)
    }
  }
}

@Composable
private fun TextSegmentView(segment: TextSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val smileyHeight = LocalTextScale.current.smileyHeight

  var layout by remember(segment) { mutableStateOf<TextLayoutResult?>(null) }

  val revealed = remember(segment) { mutableStateListOf<String>() }

  val text = if (revealed.isEmpty()) segment.text else {
    revealSpoilers(segment.text, revealed, colors.fg)
  }

  val inlineContent = remember(segment.smilies, smileyHeight) {
    segment.smilies.associate { placement ->
      placement.id to InlineTextContent(
        placeholder = Placeholder(
          width = (smileyHeight * placement.aspect).sp,
          height = smileyHeight.sp,
          placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
        children = {
          AsyncImage(
            model = placement.url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
          )
        },
      )
    }
  }

  Text(
    text = text,
    inlineContent = inlineContent,
    onTextLayout = { layout = it },
    style = TextStyle(
      fontSize = segment.fontSize,
      lineHeight = segment.lineHeight,
      color = segment.color,
      textAlign = segment.textAlign ?: TextAlign.Unspecified,
    ),
    modifier = Modifier
      .fillMaxWidth()
      .pointerInput(segment, callbacks) {
        detectTapGestures(
          onLongPress = callbacks.onLongPress?.let { handler -> { _ -> handler() } },
        ) { position ->
          val result = layout ?: return@detectTapGestures
          val offset = result.getOffsetForPosition(position)
          for (annotation in segment.text.getStringAnnotations(offset, offset)) {
            val handled = when (annotation.tag) {
              BBCodeAnnotation.LINK -> callbacks.onOpenLink?.invoke(annotation.item)
              BBCodeAnnotation.USER -> callbacks.onOpenUser?.invoke(annotation.item)
              BBCodeAnnotation.TOPIC -> callbacks.onOpenTopic?.invoke(annotation.item)
              BBCodeAnnotation.FLOOR -> callbacks.onOpenFloor?.invoke(annotation.item)
              BBCodeAnnotation.MENTION -> callbacks.onOpenMention?.invoke(annotation.item)
              BBCodeAnnotation.SPOILER -> {
                if (!revealed.remove(annotation.item)) revealed.add(annotation.item)
                Unit
              }
              else -> null
            }
            if (handled != null) break
          }
        }
      },
  )
}

private fun revealSpoilers(
  text: AnnotatedString,
  revealed: List<String>,
  color: Color,
): AnnotatedString {
  val builder = AnnotatedString.Builder(text)
  for (annotation in text.getStringAnnotations(BBCodeAnnotation.SPOILER, 0, text.length)) {
    if (annotation.item in revealed) {
      builder.addStyle(SpanStyle(color = color), annotation.start, annotation.end)
    }
  }
  return builder.toAnnotatedString()
}

internal val BLOCK_GAP = Spacing.md - 1.dp
internal val IMAGE_GAP = BLOCK_GAP

@Composable
private fun AlignBlock(segment: AlignSegment, callbacks: BBCodeCallbacks) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = when (segment.align) {
      Align.LEFT -> Alignment.Start
      Align.CENTER -> Alignment.CenterHorizontally
      Align.RIGHT -> Alignment.End
    },
    verticalArrangement = Arrangement.Top,
  ) {
    for (child in segment.body.segments) RenderSegmentView(child, callbacks)
  }
}
