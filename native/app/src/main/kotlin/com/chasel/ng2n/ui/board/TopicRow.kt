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

/**
 * 主题列表的一行 —— 直译 RN 侧 `src/ui/topic-row.tsx`,两行布局(设计稿 `isList`):
 *
 * - 标题行:彩色标题 + `[锁定]` + 附件 `+` + 来源子版块 `[…]`,四段流式排在一起
 * - 信息行:作者(匿名已还原)——推到右边—— 最后回复人 · 回复数
 *
 * **行极轻**(anzong 思路:主题列表行 7 个 view、无头像无图片):
 * 一个 Column + 一个标题 Text + 一行 meta(两枚 9dp 的 Canvas 图标 + 三段文字)。
 * 没有卡片、没有阴影、没有头像。
 */

/** 不断行空格(设计稿标题行里的 `&nbsp;`)。写成常量,免得被当成普通空格改掉。 */
private const val NBSP = ' '

/**
 * 昵称截断(原 `maxWidth:130` 像素截断的近似):9 个全角字符 ≈ 117px。
 * 合并后的右侧文本只能整体加 maxLines,像素截断会把回复数一起省略掉,所以名字这段先截。
 */
private fun clipName(name: String): String = if (name.length > 9) "${name.take(9)}…" else name

/**
 * 一行的**渲染产物**。彩色标题在数据到达时**一次**建成 [AnnotatedString],
 * 滚动路径上只是把现成的东西贴上去(anzong 四原则第一条:滚动路径零计算)。
 */
@Immutable
data class TopicRowModel(
  val topic: Topic,
  val title: AnnotatedString,
  val author: String,
  /** 最后回复人;二级列表(热帖/精华区)换成时间文案 */
  val trailing: String,
  val replies: String,
  /** 二级列表那一档:标题 16、右侧那段用 meta 色 */
  val simple: Boolean,
)

/**
 * 把一页主题建成渲染模型。
 *
 * **一次构建**:调用方在 `remember(topics, colors)` 里调它,一屏几十行只走一遍;
 * 行组件里不再有任何字符串拼接与样式判断。彩色标题的掩码解码更早——在票 10 的
 * `decodeTitleStyle`(端点解析期)就做完了,这里只是把档位翻成 [SpanStyle]。
 */
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

/** 彩色标题的掩码 → 文字样式(设计稿标题行:颜色 + 粗/斜/下划线)。 */
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
    // 合集按设计稿加粗;镜像行不额外加粗——它的粗体本来就写在服务端下发的掩码里
    fontWeight = if (style.bold || topic.isCollection) FontWeight.SemiBold else null,
    fontStyle = if (style.italic) FontStyle.Italic else null,
    textDecoration = if (style.underline) TextDecoration.Underline else null,
  )
  return buildAnnotatedString {
    withStyle(base) { append(topic.subject) }
    // 标记与标题之间用不断行空格(设计稿的 &nbsp;),窄屏不会把 [锁定] 甩到下一行。
    // 每个标记逐项写死样式抵消标题的粗/斜/下划线——它们本来就不吃标题样式
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

/** meta 行的图标字号(RN 侧 `META_ICON_SIZE`:与 12.5 正文的视觉中心对齐的那一档)。 */
private val META_ICON = 9.dp

@Composable
fun TopicRow(model: TopicRowModel, onClick: (Topic) -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = model.topic.subject) { onClick(model.topic) }
      .drawBehind {
        // 设计稿:1px 分隔线。画出来比多一个 Box 便宜
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
          // simple-list 的 when 槽:同一位置,但用 meta 色(设计稿 color:var(--meta))
          style = metaStyle(if (model.simple) colors.meta else colors.link),
        )
      }
      AppIcon(icon = Ng2nIcon.CHAT_BUBBLE, tint = colors.meta, size = META_ICON)
      Text(text = model.replies, maxLines = 1, style = metaStyle(colors.link))
    }
  }
}

/** 原 Icon(15) + gap(6) + 名字(118)的总宽。 */
private fun Modifier.widthInMaxAuthor(): Modifier = this.widthIn(max = 139.dp)

/**
 * meta 行的行高写死 17(RN 侧 `META_LINE_HEIGHT`):左右几段文字的行盒要**一样高**,
 * 否则各自居中之后基线会错开(作者名与回复数一高一低)。
 */
private val META_LINE_HEIGHT = 17.sp

private fun metaStyle(color: androidx.compose.ui.graphics.Color) = TextStyle(
  fontSize = Typo.listMeta.size,
  lineHeight = META_LINE_HEIGHT,
  color = color,
)
