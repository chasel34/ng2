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

/**
 * 主题三屏的「壳」:顶栏、页码条、两条提示条、上次读到浮条、失败页。
 *
 * **归属说明**:顶栏 / 菜单 / 对话框这几样在 RN 侧是 `src/ui/` 下的公共组件
 * (`top-bar.tsx` / `menu.tsx` / `input-dialog.tsx` …),原生这边完整的公共 UI 体系
 * 归**票 17**。本票只做主题三屏用得到的那一份、就近放在 `ui/topic/`,避免与
 * 并行开工的票 16/17 抢同一批文件;票 17 铺开时把它们提升到 `ui/common/` 即可。
 */

/** 设计稿:顶栏一行 54 高。 */
private val TOP_BAR_HEIGHT = 54.dp

/**
 * 顶栏色块。状态栏是透明的(edge-to-edge),所以顶栏自己撑开安全区高度。
 *
 * @param below 顶栏色块里、行下面的东西(详情页的页码条)
 */
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

/**
 * 顶栏圆形图标按钮。设计稿两档:最左边那枚(返回)46,右侧动作钮 44。
 */
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

/**
 * 顶栏标题。详情页那一档 16.5/600(标题后面还跟着两枚图标,所以再矮半档)。
 *
 * [maxWidth] 是设计稿给的截断宽度(详情 190),语义与 RN 侧
 * `top-bar.tsx` 的 `maxWidth` + `flexShrink:1` 一致:**上限**而不是定宽 ——
 * 短标题照样只占自己那么宽,右边的图标不会被顶开(票 39)。
 */
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

/**
 * 顶栏下面那条页码条(设计稿 isArticle 的第二行)。
 *
 * 页数多的帖子有上千页,全铺出来会卡,所以只画一个围绕当前页的窗口,
 * 首尾两页固定露出来 —— 跳到最后一页是最常用的动作之一([visiblePages])。
 */
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

  // 滚到当前页时给它左边留几格的余量,不然当前页永远贴在最左边
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
      // 窗口跳号的地方画个省略号,免得 3 后面直接跟 128 看着像少了页
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

/** 顶栏上「当前选中」那一格的底色(设计稿页码格与抽屉当前账号都用它)。 */
private val TOPBAR_OVERLAY = Color(0x1FFFFFFF)

/**
 * 数据源降级提示条(设计稿 fallbackBar)。
 *
 * 钉在页码条下面而不是跟着列表滚:它说的是「整页数据的来源」,不是某一楼的事。
 */
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

/** 整帖缓存进度条:跟数据源提示条同一条带子的语言,右侧是「停止」。 */
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

/** 只看该楼提示条。 */
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

/** 只看此人过滤条(设计稿 onlyUser):退出即恢复全楼。 */
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

/** 设计稿 fallbackBar 的底:内距 11 12 11 14、primary-c 底、底边一条 divider。 */
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

/**
 * 「上次读到第 N 楼」提示条(设计稿 progressTip),**浮层版**:不占布局,
 * 一滚动 / 一翻页 / 5 秒没人理就淡出。
 *
 * 进场照设计稿 progressTip:`.28s` 上浮 14px 淡入(omup);退场用短一档(`.2s`)
 * 原路淡回去 —— 退场比进场快是通例,让位给用户正在做的那件事。
 *
 * **5 秒兜底从「进场动画真的跑完」起算**,不是从「该显示了」起算。两者平时只差一帧,
 * 但首屏重的时候能差出好几秒(RN 侧 2026-08-20 录屏抓到过只亮 0.8 秒就走的一次)。
 * `Animatable.animateTo` 挂起到动画真的跑完才返回,正好是那个判据。
 */
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
      // 亮够 5 秒还没人理就自己走
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

/** 设计稿 `duration.notice` / `duration.base` / `RISE_OFFSET`。 */
private const val NOTICE_MS = 280
private const val BASE_MS = 200
private const val RISE_OFFSET_PX = 14f * 3f

/**
 * 「上次读到」浮条的兜底寿命。它盖在楼层上,不该一直杵着;
 * 5s 足够看清一句话并决定要不要点,再久就只剩碍事了。
 */
private const val RESUME_AUTO_HIDE_MS = 5000L

/**
 * 「加载失败」页(设计稿 isError 屏)。
 *
 * 反封锁链(ADR-0002)把格式 × 域名的组合、换账号、Web 反解都试遍还是不行时落到这里:
 * 说清楚服务端返回了什么 + 重试 / 用网页版打开 / 重新登录三个出路。
 * 它是屏内的一块而不是一个路由:顶栏与页码条仍在,失败的只是内容区。
 */
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

/** 「这一页没有楼层」。 */
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

/** 被屏蔽规则挡下的楼层折成一行灰字(点一下就地展开)。 */
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

/** 主题屏根底色:「使用纯色背景」开着时把奶油底换成卡片那一档纯色。 */
fun rootBackground(colors: Ng2nColors, solid: Boolean): Color =
  if (solid) colors.surface else colors.bg
