package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Typo

/**
 * 顶栏色块。直译 RN 侧 `src/ui/top-bar.tsx`:一行 54 高;状态栏是透明的
 * (edge-to-edge),所以顶栏自己撑开安全区高度。[below] 是行下面那一块
 * (首页的分类 tab 条、二级页的副标题条)。
 */
private val BAR_HEIGHT = 54.dp

@Composable
fun TopBar(
  modifier: Modifier = Modifier,
  paddingHorizontal: Dp = 6.dp,
  below: @Composable (() -> Unit)? = null,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = LocalNg2nColors.current
  Column(modifier.fillMaxWidth().background(colors.topbar)) {
    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(BAR_HEIGHT)
        .padding(horizontal = paddingHorizontal),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
    below?.invoke()
  }
}

/**
 * 顶栏图标钮。[box] 是触控盒边长 —— 设计稿里最左边那枚(菜单/返回)是 46,
 * 右侧的动作钮是 44;[size] 是图标本身的字号(设计稿逐个标了)。
 */
@Composable
fun TopBarButton(
  icon: Ng2nIcon,
  size: Dp,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  box: Dp = 44.dp,
  tint: Color? = null,
) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .size(box)
      .clip(CircleShape)
      .clickable(onClickLabel = contentDescription, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    AppIcon(icon = icon, tint = tint ?: colors.onTopbar, size = size)
  }
}

enum class TopBarTitleVariant { MAIN, SUB }

/**
 * 顶栏标题。设计稿按屏分档:首页 18/600,二级页 17/600。
 * [maxWidth] 是设计稿给二级页标的截断宽度(主题列表 150),右边三枚图标才排得开。
 */
@Composable
fun TopBarTitle(
  text: String,
  modifier: Modifier = Modifier,
  variant: TopBarTitleVariant = TopBarTitleVariant.MAIN,
  maxWidth: Dp? = null,
) {
  val colors = LocalNg2nColors.current
  val token = if (variant == TopBarTitleVariant.MAIN) Typo.title else Typo.subTitle
  Text(
    text = text,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(
      fontSize = token.size,
      lineHeight = token.lineHeight,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = if (variant == TopBarTitleVariant.MAIN) 0.2.sp else 0.sp,
      color = colors.onTopbar,
    ),
    modifier = if (maxWidth == null) modifier else modifier.widthIn(max = maxWidth),
  )
}
