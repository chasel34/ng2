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

/**
 * 楼层里三块「装着正文的容器」:签名档、贴条区、热门回复折叠区。
 *
 * 楼层卡片本体(头像/作者行/赞踩/菜单)归**票 13**;这三块单独放在渲染器这边,
 * 是因为它们的内容都是 BBCode、都复用 [BBCodeContent],而票 13 只需要摆位置。
 * RN 侧原件:`src/ui/floor-card.tsx` 的 `Signature`/`NoteList` 与
 * `src/app/topic/[tid].tsx` 的 `HotReplies`。
 */

/**
 * 签名档(「显示签名档」设置)。
 *
 * 用引用块那一档字号(14/1.6),颜色压到次级——签名再长也不该抢正文。
 * 内容是 BBCode(常带图与折叠),所以还是走正文渲染器。
 *
 * @param model 用 [signatureRenderOptions] 建出来的模型(字号/颜色已经压过一档)
 */
@Composable
fun SignatureBlock(
  model: FloorRenderModel,
  modifier: Modifier = Modifier,
  callbacks: BBCodeCallbacks = BBCodeCallbacks(),
) {
  if (model.isEmpty) return
  val colors = LocalNg2nColors.current
  Column(modifier = modifier.padding(top = 10.dp).fillMaxWidth()) {
    // 与正文之间一条分隔线(设计稿 `borderTopWidth: 1`)
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

/**
 * 建签名档模型的参数:比正文小一档、颜色压到 meta。
 *
 * 签名与正文用**同一个**建模器,只是换一组底样式——`[collapse]`、`[img]`、表格
 * 在签名里照样要能用(NGA 上签名档花样比正文还多)。
 */
fun signatureRenderOptions(base: BBCodeRenderOptions): BBCodeRenderOptions = base.copy(
  bodyFontSize = Typo.quoteBody.size.value,
  // 14 · 1.6 = 22.4,与 `Typo.quoteBody` 同
  bodyLineHeight = Typo.quoteBody.lineHeight.value / Typo.quoteBody.size.value,
  colors = base.colors.copy(fg = base.colors.meta),
)

/** 贴条区里的一条:谁 + 已经压成一行的正文。 */
@Immutable
data class CommentEntry(
  val id: String,
  val author: String,
  /** 已经过 [plainTextOf] 压平的内容 */
  val text: String,
)

/**
 * 贴条区(设计稿:surface2 底、圆角 12 的一块,每条一行「谁:内容」)。
 *
 * 贴条正文里常带一整段 `[b]Reply to …[/b]` 引用头,连同图片一起展开会把这一小块撑爆,
 * 所以压成纯文本一行(引用关系在回复链里看)——压平用 [plainTextOf],
 * 而且要在**后台**压好再传进来,别在 composition 里解析 BBCode。
 */
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

/**
 * 热门回复区:服务端只在主楼里标,独立成一块,默认收起。
 *
 * 里面装的是**楼层卡片**(票 13 的 `FloorCard`),所以内容用 slot 交出去——
 * 渲染器不认识楼层。
 */
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

/** 设计稿 `duration.base`。 */
private const val HOT_REPLIES_MS = 200
