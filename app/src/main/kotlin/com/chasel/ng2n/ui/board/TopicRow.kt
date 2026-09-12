package com.chasel.ng2n.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.local.TitleColor
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Ng2nTitleColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import com.chasel.ng2n.ui.common.rowClickable

private const val NBSP = ' '

private fun clipName(name: String): String = if (name.length > 9) "${name.take(9)}…" else name

@Immutable
data class TopicRowModel(
  val topic: Topic,
  val title: AnnotatedString,
  val author: String,
  val trailing: String,
  val replies: String,
  val simple: Boolean,
)

fun buildTopicRows(
  topics: List<Topic>,
  colors: Ng2nColors,
  titleColors: Ng2nTitleColors,
  simple: Boolean = false,
  timeOf: ((Topic) -> String)? = null,
): List<TopicRowModel> = topics.map { topic ->
  TopicRowModel(
    topic = topic,
    title = buildTopicTitle(topic, colors, titleColors),
    author = topic.author,
    trailing = clipName(timeOf?.invoke(topic) ?: topic.lastPoster.orEmpty()),
    replies = topic.replies.toString(),
    simple = simple,
  )
}

private fun buildTopicTitle(
  topic: Topic,
  colors: Ng2nColors,
  titleColors: Ng2nTitleColors,
): AnnotatedString {
  val style = topic.titleStyle
  val base = SpanStyle(
    color = when (style.color) {
      TitleColor.RED -> titleColors.red
      TitleColor.BLUE -> titleColors.blue
      TitleColor.GREEN -> titleColors.green
      TitleColor.ORANGE -> titleColors.orange
      TitleColor.SILVER -> titleColors.silver
      null -> colors.fg
    },
    fontWeight = if (style.bold || topic.isCollection) FontWeight.SemiBold else null,
    fontStyle = if (style.italic) FontStyle.Italic else null,
    textDecoration = if (style.underline) TextDecoration.Underline else null,
  )
  return buildAnnotatedString {
    withStyle(base) { append(topic.subject) }
    if (topic.locked) {
      withStyle(SpanStyle(color = colors.danger, fontWeight = FontWeight.SemiBold)) {
        append("$NBSP[锁定]")
      }
    }
    if (topic.hasAttachment) {
      withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
        append("$NBSP+")
      }
    }
    val parent = topic.parent
    if (parent != null) {
      withStyle(SpanStyle(color = colors.tag, fontWeight = FontWeight.Normal)) {
        append("$NBSP[${parent.name}]")
      }
    }
  }
}

private val META_ICON = 9.dp

@Composable
fun TopicRow(
  model: TopicRowModel,
  onClick: (Topic) -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: ((Topic) -> Unit)? = null,
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .rowClickable(
        onClickLabel = model.topic.subject,
        onLongClick = onLongClick?.let { handler -> { handler(model.topic) } },
        onLongClickLabel = if (onLongClick == null) null else "更多操作",
      ) { onClick(model.topic) }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
  ) {
    val titleToken = if (model.simple) Typo.listTitle else Typo.topicTitle
    Text(
      text = model.title,
      style = TextStyle(fontSize = titleToken.size, lineHeight = titleToken.lineHeight),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      AppIcon(icon = Ng2nIcon.PERSON, tint = colors.meta, size = META_ICON)
      Text(
        text = model.author,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthInMaxAuthor(),
        style = metaStyle(colors.link),
      )
      Spacer(Modifier.weight(1f))
      if (model.trailing.isNotEmpty()) {
        Text(
          text = model.trailing,
          maxLines = 1,
          style = metaStyle(if (model.simple) colors.meta else colors.link),
        )
      }
      AppIcon(icon = Ng2nIcon.CHAT_BUBBLE, tint = colors.meta, size = META_ICON)
      Text(text = model.replies, maxLines = 1, style = metaStyle(colors.link))
    }
  }
}

private fun Modifier.widthInMaxAuthor(): Modifier = this.widthIn(max = 139.dp)

private val META_LINE_HEIGHT = 17.sp

private fun metaStyle(color: androidx.compose.ui.graphics.Color) = TextStyle(
  fontSize = Typo.listMeta.size,
  lineHeight = META_LINE_HEIGHT,
  color = color,
)
