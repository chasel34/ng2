package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.describeFetchFailure
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

private val EMPTY_ICON_SIZE = 40.dp

enum class StateVariant {
  SCREEN,

  INLINE,

  INLINE_HEAD,
}

data class StateAction(val label: String, val onClick: () -> Unit)

@Composable
private fun StateBox(
  variant: StateVariant,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val base = when (variant) {
    StateVariant.SCREEN -> Modifier.fillMaxSize().padding(Spacing.xl)
    StateVariant.INLINE ->
      Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = Spacing.xl)
    StateVariant.INLINE_HEAD -> Modifier
      .fillMaxWidth()
      .padding(top = 60.dp, bottom = Spacing.xl, start = Spacing.xl, end = Spacing.xl)
  }
  Column(
    modifier = base,
    verticalArrangement = if (variant == StateVariant.SCREEN) {
      Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically)
    } else {
      Arrangement.spacedBy(Spacing.md)
    },
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
  )
}

@Composable
fun EmptyState(
  icon: Ng2nIcon,
  text: String,
  modifier: Modifier = Modifier,
  action: StateAction? = null,
  variant: StateVariant = StateVariant.SCREEN,
) {
  val colors = LocalNg2nColors.current
  Box(modifier) {
    StateBox(variant) {
      AppIcon(icon = icon, tint = colors.meta, size = EMPTY_ICON_SIZE)
      Text(
        text = text,
        textAlign = TextAlign.Center,
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.fg2,
        ),
      )
      if (action != null) PillButton(action)
    }
  }
}

@Composable
fun LoadingState(
  modifier: Modifier = Modifier,
  text: String? = null,
  variant: StateVariant = StateVariant.SCREEN,
) {
  val colors = LocalNg2nColors.current
  Box(modifier) {
    StateBox(variant) {
      CircularProgressIndicator(color = colors.primary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
      if (text != null) {
        Text(
          text = text,
          textAlign = TextAlign.Center,
          style = TextStyle(
            fontSize = Typo.notice.size,
            lineHeight = Typo.notice.lineHeight,
            color = colors.fg2,
          ),
        )
      }
    }
  }
}

@Composable
fun LoadingFooter(text: String, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xl),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(color = colors.meta, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    Text(
      text = text,
      style = TextStyle(fontSize = Typo.listMeta.size, lineHeight = Typo.listMeta.lineHeight, color = colors.meta),
    )
  }
}

@Composable
fun PillButton(action: StateAction, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .height(40.dp)
      .clip(RoundedCornerShape(Radius.full))
      .background(colors.primary)
      .clickable(onClickLabel = action.label, onClick = action.onClick)
      .padding(horizontal = Spacing.xl),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = action.label,
      style = TextStyle(
        fontSize = Typo.drawerItem.size,
        lineHeight = Typo.drawerItem.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
      ),
    )
  }
}

@Composable
fun LoadFailedNotice(
  error: Throwable?,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  variant: StateVariant = StateVariant.INLINE,
) {
  EmptyState(
    icon = Ng2nIcon.CLOUD_OFF,
    text = failureText(error),
    action = StateAction("重试", onRetry),
    variant = variant,
    modifier = modifier,
  )
}

const val FAILURE_FALLBACK = "没能拿到数据"

fun failureText(error: Throwable?): String {
  if (error !is NgaError) return FAILURE_FALLBACK
  val copy = describeFetchFailure(error.kind.wire, error.status, error.text)
  val headline = copy.headline.ifBlank { FAILURE_FALLBACK }
  return if (copy.code == null) headline else "$headline ${copy.code}"
}
