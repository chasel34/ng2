package com.chasel.ng2n.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 顶栏右上角的弹出菜单 —— 直译 RN 侧 `ui/menu.tsx`。
 * 设计稿:右侧留 8,圆角 14,条目高 50,弹出 `.16s` 的 ompop。
 *
 * 面板设了 max-height 520 + 可滚:条目多到顶格时要够得着最下面几条。
 */
data class MenuItem(
  val key: String,
  val label: String,
  /** 这一条起一个新分组:上面画一条分割线 */
  val gapBefore: Boolean = false,
  /** 一组互斥选项里当前生效的那条(排序切换),用主题色 + 加粗标出来 */
  val selected: Boolean = false,
  val onClick: () -> Unit,
)

/**
 * 面板宽度:**最小 186,再宽就跟着最长那条走**(RN 侧 `ui/menu.tsx` 的
 * `minWidth: 186`,面板本身是 wrap-content)。
 *
 * 票 43:原来这里写死 208,比 Expo 宽 22 —— 而楼层菜单那一份(`ui/topic/TopicOverlays.kt`)
 * 因为条目 `fillMaxWidth` 又没限宽,直接铺到 394 占满屏。两处现在共用这三档,
 * 免得同一个 app 里两个菜单长得不一样。
 */
internal val MENU_MIN_WIDTH = 186.dp
internal val MENU_MAX_HEIGHT = 520.dp
internal val MENU_ITEM_HEIGHT = 50.dp

/** 条目左右内距(RN 侧 `item.paddingHorizontal: 22`)。 */
internal val MENU_ITEM_PADDING = 22.dp

@Composable
fun OverflowMenu(
  open: Boolean,
  onDismiss: () -> Unit,
  items: List<MenuItem>,
  modifier: Modifier = Modifier,
) {
  if (!open) return
  val colors = LocalNg2nColors.current
  BackHandler(enabled = true, onBack = onDismiss)

  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { started = true }
  val pop by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(Motion.DURATION_MENU, easing = Motion.easeStandard),
    label = "menu-pop",
  )

  // 顶栏高度 = 状态栏 + 54,菜单压在它下面 6
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

  Box(modifier.fillMaxSize()) {
    Box(
      Modifier
        .matchParentSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClickLabel = "关闭菜单",
          onClick = onDismiss,
        ),
    )
    Column(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = statusBar + 6.dp, end = Spacing.sm)
        // 最小 186 + 内容撑宽:`width(IntrinsicSize.Max)` 量的是最长那条条目
        // (文字 + 左右 22 内距),再由 defaultMinSize 兜到 186 —— 与 RN 的
        // `minWidth: 186` + wrap-content 同义。条目的 fillMaxWidth 也因此有了边界。
        .defaultMinSize(minWidth = MENU_MIN_WIDTH)
        .width(IntrinsicSize.Max)
        .graphicsLayer {
          val scale = Motion.POP_SCALE + (1f - Motion.POP_SCALE) * pop
          scaleX = scale
          scaleY = scale
          transformOrigin = TransformOrigin(1f, 0f)
        }
        .alpha(pop)
        .shadow(Elevation.level2, RoundedCornerShape(Radius.lg))
        .clip(RoundedCornerShape(Radius.lg))
        .background(colors.menu)
        .heightIn(max = MENU_MAX_HEIGHT)
        .verticalScroll(rememberScrollState()),
    ) {
      items.forEachIndexed { index, item ->
        // 第一条上面不画:面板顶上贴着一条线没有分组意义,还会怼到圆角上
        if (item.gapBefore && index > 0) {
          Box(
            Modifier
              .fillMaxWidth()
              .padding(horizontal = Spacing.lg)
              .height(1.dp)
              .background(colors.divider),
          )
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ITEM_HEIGHT)
            .clickable(onClick = item.onClick)
            // RN 侧 `ui/menu.tsx` 的 `item.paddingHorizontal: 22`(票 43:原来是 20)
            .padding(horizontal = MENU_ITEM_PADDING),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            text = item.label,
            style = TextStyle(
              fontSize = Typo.menuItem.size,
              lineHeight = Typo.menuItem.lineHeight,
              fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
              color = if (item.selected) colors.primary else colors.fg,
            ),
          )
        }
      }
    }
  }
}
