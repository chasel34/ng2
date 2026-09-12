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

data class MenuItem(
  val key: String,
  val label: String,
  val gapBefore: Boolean = false,
  val selected: Boolean = false,
  val onClick: () -> Unit,
)

internal val MENU_MIN_WIDTH = 186.dp
internal val MENU_MAX_HEIGHT = 520.dp
internal val MENU_ITEM_HEIGHT = 50.dp

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
