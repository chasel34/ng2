package com.chasel.ng2n.ui.topic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.net.describeFetchFailure
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

private val TOP_BAR_HEIGHT = 54.dp

@Composable
fun TopicTopBar(
  paddingHorizontal: Dp = 4.dp,
  below: (@Composable () -> Unit)? = null,
  row: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
  val colors = LocalNg2nColors.current
  val statusBar = WindowInsets.statusBars.asPaddingValues()
  Column(Modifier.fillMaxWidth().background(colors.topbar)) {
    Box(Modifier.height(statusBar.calculateTopPadding()))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(TOP_BAR_HEIGHT)
        .padding(horizontal = paddingHorizontal),
      verticalAlignment = Alignment.CenterVertically,
      content = row,
    )
    below?.invoke()
  }
}

@Composable
fun TopBarButton(
  onClick: () -> Unit,
  label: String,
  box: Dp = 44.dp,
  modifier: Modifier = Modifier,
  icon: @Composable () -> Unit,
) {
  Box(
    modifier = modifier
      .size(box)
      .clip(CircleShape)
      .clickable(onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) { icon() }
}

@Composable
fun TopBarTitle(text: String, modifier: Modifier = Modifier, maxWidth: Dp? = null) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    fontSize = 16.5.sp(),
    fontWeight = FontWeight.SemiBold,
    color = colors.onTopbar,
    modifier = (if (maxWidth == null) modifier else modifier.widthIn(max = maxWidth))
      .padding(start = Spacing.xs),
  )
}

private fun Double.sp() = androidx.compose.ui.unit.TextUnit(
  toFloat(),
  androidx.compose.ui.unit.TextUnitType.Sp,
)

@Composable
fun PageBar(
  page: Int,
  totalPages: Int,
  onPick: (Int) -> Unit,
  onJump: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val scroll = rememberScrollState()
  val pages = remember(page, totalPages) { visiblePages(page, totalPages) }

  LaunchedEffect(page, totalPages) {
    val index = pages.indexOf(page).takeIf { it >= 0 } ?: return@LaunchedEffect
    val approx = ((index - SCROLL_LEAD) * (CHIP_MIN_WIDTH + CHIP_GAP).value).toInt()
    scroll.animateScrollTo(approx.coerceAtLeast(0))
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(scroll)
      .padding(horizontal = 10.dp)
      .padding(bottom = Spacing.sm),
    horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    pages.forEachIndexed { index, value ->
      if (index > 0 && value - pages[index - 1] > 1) {
        Text("…", color = colors.onTopbar.copy(alpha = 0.5f), fontSize = Typo.caption.size)
      }
      val active = value == page
      Box(
        modifier = Modifier
          .defaultMinSize(minWidth = CHIP_MIN_WIDTH)
          .height(CHIP_HEIGHT)
          .clip(RoundedCornerShape(Radius.xs))
          .background(if (active) TOPBAR_OVERLAY else Color.Transparent)
          .clickable { onPick(value) }
          .padding(horizontal = 9.dp)
          .semantics { contentDescription = "第 $value 页" },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = value.toString(),
          fontSize = Typo.caption.size,
          color = colors.onTopbar,
          modifier = Modifier.alpha(if (active) 1f else 0.62f),
        )
      }
    }
    Row(
      modifier = Modifier
        .height(CHIP_HEIGHT)
        .clip(RoundedCornerShape(Radius.xs))
        .clickable(onClick = onJump)
        .padding(horizontal = 10.dp)
        .semantics { contentDescription = "跳页" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
      JumpIcon(tint = colors.onTopbar)
      Text(
        text = "跳页",
        fontSize = Typo.listMeta.size,
        color = colors.onTopbar,
        modifier = Modifier.alpha(0.8f),
      )
    }
  }
}

private val CHIP_HEIGHT = 28.dp
private val CHIP_MIN_WIDTH = 30.dp
private val CHIP_GAP = 6.dp
private const val SCROLL_LEAD = 2

private val TOPBAR_OVERLAY = Color(0x1FFFFFFF)

@Composable
fun SourceNoticeBar(
  source: TopicSource,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val cache = source == TopicSource.CACHE
  NoticeBar {
    if (cache) CloudOffIcon(tint = colors.primary) else InfoIcon(tint = colors.primary)
    Text(
      text = buildAnnotatedString {
        if (cache) {
          append("在线拿不到这一页,当前是")
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("缓存数据") }
        } else {
          append("原生解析失败,已切换为")
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("网页数据源") }
          append("显示")
        }
      },
      fontSize = Typo.listMeta.size,
      lineHeight = Typo.listMeta.lineHeight,
      color = colors.fg,
      modifier = Modifier.weight(1f),
    )
    NoticeAction(text = if (cache) "重新联网" else "重试原生", onClick = onRetry)
    Box(
      modifier = Modifier
        .clip(CircleShape)
        .clickable(onClick = onDismiss)
        .padding(Spacing.xs)
        .semantics { contentDescription = "关闭提示" },
    ) { CloseIcon(tint = colors.meta) }
  }
}

@Composable
fun CacheProgressBar(done: Int, total: Int, onStop: () -> Unit) {
  val colors = LocalNg2nColors.current
  NoticeBar {
    DownloadIcon(tint = colors.primary)
    Text(
      text = buildAnnotatedString {
        append("正在缓存整帖 ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(done.toString()) }
        append(" / $total 页")
      },
      fontSize = Typo.listMeta.size,
      color = colors.fg,
      modifier = Modifier.weight(1f),
    )
    NoticeAction(text = "停止", onClick = onStop)
  }
}

@Composable
fun OnlyFloorBar(onShowAll: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.primaryContainer)
      .clickable(onClick = onShowAll)
      .padding(horizontal = Spacing.lg, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FilterIcon(tint = colors.primary, size = 15.dp)
    Text("只看该楼", fontSize = Typo.listMeta.size, color = colors.primary, modifier = Modifier.weight(1f))
    Text(
      "看全部",
      fontSize = Typo.listMeta.size,
      fontWeight = FontWeight.SemiBold,
      color = colors.primary,
    )
  }
}

@Composable
fun OnlyUserBar(name: String, onExit: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .padding(top = 10.dp, start = Spacing.md, end = Spacing.md, bottom = 2.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.surface2)
      .padding(vertical = 10.dp, horizontal = Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    FilterIcon(tint = colors.primary)
    Text(
      text = buildAnnotatedString {
        append("只看 ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) }
        append(" 的发言")
      },
      fontSize = Typo.listMeta.size,
      color = colors.fg2,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "退出",
      fontSize = Typo.listMeta.size,
      fontWeight = FontWeight.Bold,
      color = colors.primary,
      modifier = Modifier
        .clip(RoundedCornerShape(Radius.xs))
        .clickable(onClick = onExit)
        .padding(horizontal = 6.dp, vertical = Spacing.xs)
        .semantics { contentDescription = "退出只看此人" },
    )
  }
}

@Composable
private fun NoticeBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
  val colors = LocalNg2nColors.current
  Column(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.primaryContainer)
        .padding(start = 14.dp, end = Spacing.md, top = 11.dp, bottom = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
    Divider()
  }
}

@Composable
private fun NoticeAction(text: String, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    fontSize = Typo.listMeta.size,
    fontWeight = FontWeight.Bold,
    color = colors.primary,
    modifier = Modifier
      .clip(RoundedCornerShape(Radius.xs))
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = Spacing.xs),
  )
}

@Composable
fun Divider(color: Color? = null) {
  val colors = LocalNg2nColors.current
  Box(Modifier.fillMaxWidth().height(1.dp).background(color ?: colors.divider))
}

@Composable
fun LastReadBanner(
  floor: Long,
  visible: Boolean,
  onAutoHide: () -> Unit,
  onJump: () -> Unit,
  onClose: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val progress = remember { Animatable(0f) }

  LaunchedEffect(visible) {
    if (visible) {
      progress.animateTo(1f, tween(NOTICE_MS))
      kotlinx.coroutines.delay(RESUME_AUTO_HIDE_MS)
      onAutoHide()
    } else {
      progress.animateTo(0f, tween(BASE_MS))
    }
  }

  if (progress.value <= 0f && !visible) return

  Row(
    modifier = Modifier
      .padding(top = 10.dp, start = Spacing.md, end = Spacing.md)
      .fillMaxWidth()
      .graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * RISE_OFFSET_PX
      }
      .clip(RoundedCornerShape(Radius.md))
      .background(colors.primaryContainer)
      .padding(start = Spacing.row, end = Spacing.md, top = 11.dp, bottom = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    BookmarkIcon(tint = colors.primary)
    Text(
      text = buildAnnotatedString {
        append("上次读到 ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("第 $floor 楼") }
      },
      fontSize = Typo.notice.size,
      color = colors.fg,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "回到那里",
      fontSize = Typo.notice.size,
      fontWeight = FontWeight.Bold,
      color = colors.primary,
      modifier = Modifier
        .clip(RoundedCornerShape(Radius.xs))
        .clickable(onClick = onJump)
        .padding(horizontal = 6.dp, vertical = Spacing.xs)
        .semantics { contentDescription = "回到第 $floor 楼" },
    )
    Box(
      modifier = Modifier
        .clip(CircleShape)
        .clickable(onClick = onClose)
        .padding(Spacing.xs)
        .semantics { contentDescription = "关闭提示" },
    ) { CloseIcon(tint = colors.meta) }
  }
}

private const val NOTICE_MS = 280
private const val BASE_MS = 200
private const val RISE_OFFSET_PX = 14f * 3f

private const val RESUME_AUTO_HIDE_MS = 5000L

@Composable
fun LoadFailed(
  error: Throwable?,
  onRetry: () -> Unit,
  onOpenWeb: () -> Unit,
  onRelogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LocalNg2nColors.current
  val failure = remember(error) { describeFetchFailure(error) }
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(Spacing.xl),
    verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.surface2),
      contentAlignment = Alignment.Center,
    ) { CloudOffIcon(tint = colors.meta, size = 34.dp) }
    Text(
      text = "这一页没能加载出来",
      fontSize = Typo.section.size,
      fontWeight = FontWeight.SemiBold,
      color = colors.fg,
    )
    Text(
      text = buildAnnotatedString {
        append(failure.headline)
        failure.code?.let {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(" $it") }
        }
        append("\n")
        append(failure.hint)
      },
      fontSize = Typo.notice.size,
      lineHeight = Typo.notice.lineHeight,
      color = colors.fg2,
      textAlign = TextAlign.Center,
    )
    PrimaryButton(text = "重试", onClick = onRetry)
    SecondaryButton(text = "用网页版打开", onClick = onOpenWeb)
    Text(
      text = "重新登录账号",
      fontSize = Typo.listMeta.size,
      color = colors.meta,
      modifier = Modifier
        .clip(RoundedCornerShape(Radius.xs))
        .clickable(onClick = onRelogin)
        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
  }
}

@Composable
fun EmptyPage(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = modifier.fillMaxSize().padding(Spacing.xl),
    verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    EmptyArticleIcon(tint = colors.meta)
    Text("这一页没有楼层", fontSize = Typo.notice.size, color = colors.fg2)
    PrimaryButton(text = "刷新", onClick = onRefresh)
  }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .height(40.dp)
      .clip(CircleShape)
      .background(colors.primary)
      .clickable(onClick = onClick)
      .padding(horizontal = Spacing.xl),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = colors.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = Typo.notice.size)
  }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = Modifier
      .height(40.dp)
      .clip(CircleShape)
      .background(colors.surface2)
      .clickable(onClick = onClick)
      .padding(horizontal = Spacing.xl),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = Typo.notice.size)
  }
}

@Composable
fun BlockedFloorRow(text: String, onExpand: () -> Unit) {
  val colors = LocalNg2nColors.current
  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surface2)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = androidx.compose.material3.ripple(),
          onClick = onExpand,
        )
        .padding(horizontal = Spacing.row, vertical = Spacing.md)
        .semantics { contentDescription = "展开$text" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      BlockIcon(tint = colors.meta)
      Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = Typo.listMeta.size,
        color = colors.meta,
        modifier = Modifier.weight(1f),
      )
      Text("展开", fontSize = Typo.listMeta.size, fontWeight = FontWeight.Bold, color = colors.primary)
    }
    Divider()
  }
}

fun rootBackground(colors: Ng2nColors, solid: Boolean): Color =
  if (solid) colors.surface else colors.bg
