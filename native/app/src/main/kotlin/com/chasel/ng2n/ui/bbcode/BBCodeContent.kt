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
import com.chasel.ng2n.ui.image.PostImage
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalTextScale
import com.chasel.ng2n.ui.theme.Spacing

/**
 * 渲染成品 → 屏上的东西(ADR-0001 的 UI 半边)。
 *
 * 这里**不做任何转换**:段序列已经由 [RenderModelBuilder] 在后台建好,composition 只负责
 * 把每一段贴到对应的组件上。滚动时被回收/重组的路径上没有解析、没有字符串拼接、
 * 没有 URL 计算 —— anzong 研读报告里「顺」的第一条。
 *
 * 段与段之间的上距由每种块自己带(与 RN 版一致:`marginTop: 11` 之类摞在块上),
 * 这里的 [Column] 不给 spacing —— 否则纯文本段之间会多出一截空。
 */
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

/**
 * 正文里的点击去处。全部可空/带默认值:签名档、贴条摘要这些地方不接导航,
 * 少传就是不响应,不必造一堆空函数。
 */
@Immutable
data class BBCodeCallbacks(
  /** `[url]`,站外链接 */
  val onOpenLink: ((String) -> Unit)? = null,
  /** `[uid]`,进用户资料 */
  val onOpenUser: ((String) -> Unit)? = null,
  /** `[tid]`,进主题 */
  val onOpenTopic: ((String) -> Unit)? = null,
  /** `[pid]`,进那一楼;参数是逗号分隔的 `pid,tid,page`(原样带走,票 13 自己解) */
  val onOpenFloor: ((String) -> Unit)? = null,
  /** `[@用户名]` */
  val onOpenMention: ((String) -> Unit)? = null,
  /** 点正文图 / 相册图,进大图查看器(票 12) */
  val onOpenImage: ((String) -> Unit)? = null,
  /** `[flash]` 媒体卡与 `[attach]` 附件卡:交给系统打开 */
  val onOpenExternal: ((String) -> Unit)? = null,
  /** 引用卡底部的「查看对话链(N 层)」;给了才画那一行 */
  val onOpenChain: ((QuoteRef) -> Unit)? = null,
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
      // 上距直接摞在 PostImage 的根节点上,不为了一条 marginTop 多套一层容器——
      // 图多的楼层里每张图都省一个节点(量算 + 绘制)
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

/**
 * 一段行内文字。
 *
 * 两件事在这儿收口:
 *
 * 1. **表情内联**。`inlineContent` 的 key 就是模型里登记的 id,高度跟着「表情大小」
 *    设置走,宽度按生成期读出的原始比例算 —— 与 RN 侧 `smiley.tsx` 同一个口径,
 *    只是那边要运行期查 bundle 元数据,这边是编译期常量。
 * 2. **点击分派**。链接/uid/tid/pid/@ 都是 `pushStringAnnotation` 钉在区间上的,
 *    点到哪个字符就查那个位置有没有 annotation。用 `detectTapGestures` 而不是
 *    `ClickableText`(后者已废弃)也不用 `LinkAnnotation`:站内引用点了是**导航**,
 *    不是开 URL,统一走一套回调票 13 才好接。
 */
@Composable
private fun TextSegmentView(segment: TextSegment, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val smileyHeight = LocalTextScale.current.smileyHeight

  var layout by remember(segment) { mutableStateOf<TextLayoutResult?>(null) }

  /**
   * 已经翻开的防剧透段(annotation 里的序号)。
   *
   * 记在这里而不是建模期:同一份模型在两处显示(楼层 + 回复链预览)时,
   * 翻开哪一段是**这一处**的事;而且模型要能常驻页级缓存,不该被点击弄脏。
   */
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
      // TextStyle 的 textAlign 不收 null(Compose 用 TextAlign.Unspecified 表示「跟随默认」)
      textAlign = segment.textAlign ?: TextAlign.Unspecified,
    ),
    modifier = Modifier
      .fillMaxWidth()
      .pointerInput(segment, callbacks) {
        detectTapGestures { position ->
          val result = layout ?: return@detectTapGestures
          val offset = result.getOffsetForPosition(position)
          for (annotation in segment.text.getStringAnnotations(offset, offset)) {
            val handled = when (annotation.tag) {
              BBCodeAnnotation.LINK -> callbacks.onOpenLink?.invoke(annotation.item)
              BBCodeAnnotation.USER -> callbacks.onOpenUser?.invoke(annotation.item)
              BBCodeAnnotation.TOPIC -> callbacks.onOpenTopic?.invoke(annotation.item)
              BBCodeAnnotation.FLOOR -> callbacks.onOpenFloor?.invoke(annotation.item)
              BBCodeAnnotation.MENTION -> callbacks.onOpenMention?.invoke(annotation.item)
              // 防剧透:点一下把这一段翻出来;再点一下盖回去
              BBCodeAnnotation.SPOILER -> {
                if (!revealed.remove(annotation.item)) revealed.add(annotation.item)
                Unit
              }
              else -> null
            }
            // 嵌套的 annotation(链接里裹着防剧透)只响应最内层那一个
            if (handled != null) break
          }
        }
      },
  )
}

/**
 * 把已翻开的防剧透段改回正文色。
 *
 * 在原串上**再叠一层** span 而不是重建整段:Compose 解析样式时后加的赢,
 * 所以叠一条 `SpanStyle(color = fg)` 就把白字盖过去了,原来的粗体/字号一个不丢。
 */
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

/** 段与段之间的上距,与 RN 侧那一串 `marginTop: 11` 同值(设计稿引用块/图片/表格上距)。 */
internal val BLOCK_GAP = Spacing.md - 1.dp
internal val IMAGE_GAP = BLOCK_GAP

@Composable
private fun AlignBlock(segment: AlignSegment, callbacks: BBCodeCallbacks) {
  // 对齐要同时作用在容器和文字上,只给一个都不够:容器管块级子元素(图片、卡片),
  // 文字那半在建模期就压进了 TextSegment.textAlign
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
