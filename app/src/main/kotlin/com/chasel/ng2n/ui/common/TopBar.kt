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
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Typo

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
    MotionIcon(icon = icon, tint = tint ?: colors.onTopbar, size = size)
  }
}

enum class TopBarTitleVariant { MAIN, SUB }

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
