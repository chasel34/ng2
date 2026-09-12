package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.home.initialOf
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import com.chasel.ng2n.ui.theme.avatarColorFor
import com.chasel.ng2n.ui.common.rowClickable
import kotlinx.coroutines.delay

val LIST_TAIL_HEIGHT: Dp = 26.dp

@Composable
fun ListSubtitle(
  text: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .let { if (onClick == null) it else it.rowClickable(onClickLabel = text, onClick = onClick) }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = 11.dp, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(
      text = text,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
      style = TextStyle(
        fontSize = Typo.cardMeta.size,
        lineHeight = Typo.cardMeta.lineHeight,
        color = colors.meta,
      ),
    )
    trailing?.invoke()
  }
}

@Composable
fun ListTail(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(LIST_TAIL_HEIGHT))
}

@Composable
fun InitialAvatar(
  name: String,
  colorKey: String,
  size: Dp,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
) {
  Box(
    modifier = modifier.size(size).clip(shape).background(avatarColorFor(colorKey)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initialOf(name),
      style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White),
    )
  }
}

@Composable
fun rememberMinuteTick(): State<Long> = produceState(initialValue = nowSeconds()) {
  while (true) {
    delay(MINUTE_MS)
    value = nowSeconds()
  }
}

private const val MINUTE_MS = 60_000L

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
