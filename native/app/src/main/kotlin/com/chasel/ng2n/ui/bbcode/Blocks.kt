package com.chasel.ng2n.ui.bbcode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
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
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 结构类块的容器:引用卡、折叠块、列表、表格、`[lessernuke]` 警告框、标题、分割线。
 *
 * 它们的共同点是「自己占一块、里面还有正文」——正文是模型里嵌好的
 * [FloorRenderModel],这里只管框和交互。
 */

/**
 * 引用卡片的外框:`[quote]` 与 `Reply to` 回复头共用——两者都是「这一楼在回谁」,
 * 差别只在服务端有没有给容器,视觉上没道理分成两样。
 *
 * 设计稿:引用块 11/13 内距、圆角 12、底色 quote、左侧 3 的 track 竖条。
 */
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
      // 左侧竖条:用一条 3dp 宽的背景画,比多套一个 Box 省一个节点
      .drawLeftRail(colors.track)
      .padding(start = 13.dp + QUOTE_RAIL, top = 11.dp, end = 13.dp, bottom = 11.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // 引用块里那句「Post by 谁 (时间)」是服务端塞在 BBCode 里的,原样渲染就够,
    // 不另外合成一行标题——合成的话作者名会重复出现两遍
    BBCodeContent(model = segment.body, callbacks = callbacks)

    // 「查看对话链(N 层)」入口:只有调用方接了、且这个引用块认得出 [pid] 引用时才画——
    // 手打的 [quote](没有 pid 标记)追不了链,画了也是死入口
    if (chain != null && onOpenChain != null) {
      Row(
        modifier = Modifier
          .padding(top = 2.dp)
          .clickable { onOpenChain(chain) },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ChainIcon(tint = colors.primary)
        Text(
          text = "查看对话链",
          fontSize = Typo.listMeta.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.primary,
        )
      }
    }
  }
}

private val QUOTE_RAIL = 3.dp

/**
 * 左边一条 3dp 的竖轨(设计稿 `borderLeftWidth: 3`)。
 *
 * 用 `drawBehind` 画而不是多套一个 [Box]:一楼里引用块能有好几个,
 * 每个都省一个布局节点。下面几个边框同理 —— Compose 没有 CSS 那种单边 border,
 * 手画一条矩形比 `border()` 画四条再盖住三条实在。
 */
private fun Modifier.drawLeftRail(color: Color): Modifier = drawBehind {
  drawRect(color = color, size = Size(QUOTE_RAIL.toPx(), size.height))
}

/** 底边一条 1dp 分隔线(`[h]` 标题下面那条)。 */
private fun Modifier.bottomDivider(color: Color): Modifier = drawBehind {
  val thickness = 1.dp.toPx()
  drawRect(
    color = color,
    topLeft = Offset(0f, size.height - thickness),
    size = Size(size.width, thickness),
  )
}

/** 表格单元格的右边 + 下边(左边与上边由整表的 border 画,不重复)。 */
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

/** `[h]` 与 `===标题===`:网页版是一条带下划线的小标题。 */
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

/** `======` 分割线。 */
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

/** `[list]` / `[list=1]`。`items` 已经由解析器按 `[*]` 切好。 */
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

/**
 * `[table]`。简化排版见 `Table.kt`:固定列宽 + 整表横向滚动,`rowspan` 忽略。
 *
 * 与父级横滑翻页的手势冲突:RN 那边要靠模块级计数标记 + UI 线程镜像 SharedValue,
 * 因为详情页的翻页手势是在**捕获阶段**认领的,祖先先手。原生这边对应的是
 * `requestDisallowInterceptTouchEvent`——摸到表格就请祖先让开,松手再放开。
 * 具体怎么让由票 13 决定(它才知道 Pager 长什么样),这里只把信号发出去:
 * [LocalHorizontalDragGuard]。默认实现什么都不做,demo 屏与签名档里没有 Pager。
 */
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
        // 手指一落在表格上就请祖先别拦横向手势,抬手/取消再还回去。
        // 不用 detectDragGestures:那要等到「确实横着拖了」才触发,而祖先在
        // 捕获阶段早就把手势收走了——必须在 down 那一刻就打招呼。
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
        // IntrinsicSize.Min:一行里最高的那格定行高,其余格 fillMaxHeight 跟上——
        // 不这么写 Row 的默认对齐是顶对齐,矮格子的右边框只画半截,看着像表格裂了
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

/**
 * `[lessernuke]` 是版规处罚提示,内容默认收起;`[hip]` / `[item]` 只是普通的一块。
 */
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

/** `[collapse]` / `[collapse=标题]`。默认收起,和网页版一致。 */
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

/**
 * 「一行提要 + 点开才显示内容」的那种块(RN 侧原件 `src/ui/collapsible-card.tsx`)。
 *
 * 正文里有三处长这样:`[collapse]` 折叠块、`[lessernuke]` 版规处罚提示、`[album]` 相册。
 * 三者默认都收起——折叠块是作者主动要藏,相册和处罚内容则是不该一进楼就拉图/铺开。
 *
 * 展开收起带动画:`AnimatedVisibility` 的 expand/shrink,时长取设计稿 `duration.base`
 * 200ms(RN 侧 `motion.ts`)。RN 版这块是**没有**动画的(直接 `open && <View>`),
 * 这里加上是因为原生这边不用担心 CLAUDE.md 里那条「RN Animated 预采样」的坑。
 */
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
      enter = expandVertically(tween(COLLAPSE_MS)) + fadeIn(tween(COLLAPSE_MS)),
      exit = shrinkVertically(tween(COLLAPSE_MS)) + fadeOut(tween(COLLAPSE_MS)),
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

/** 设计稿 `duration.base`。 */
private const val COLLAPSE_MS = 200

/**
 * 表格横滑与父级翻页手势的让路信号(RN 侧 `ui/horizontal-drag.ts` 的对应物)。
 *
 * 票 13 接主题详情屏时 provide 一份真实实现(拿到 Pager 的 state 之后
 * 把 `userScrollEnabled` 关掉,或按 Android 的 `requestDisallowInterceptTouchEvent`
 * 语义处理)。默认什么都不做。
 */
@Stable
interface HorizontalDragGuard {
  /** 手指落在一个要横滑的子元素上。 */
  fun begin()

  /** 抬手或手势被取消。**必须**与 [begin] 配对,否则翻页会一直被按住。 */
  fun end()
}

val LocalHorizontalDragGuard = staticCompositionLocalOf<HorizontalDragGuard> {
  object : HorizontalDragGuard {
    override fun begin() = Unit
    override fun end() = Unit
  }
}
