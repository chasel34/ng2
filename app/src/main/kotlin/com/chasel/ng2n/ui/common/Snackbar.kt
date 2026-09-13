package com.chasel.ng2n.ui.common

import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SnackbarAction(val label: String, val onClick: () -> Unit)

data class SnackbarItem(
  val id: Long,
  val text: String,
  val action: SnackbarAction? = null,
)

object Snackbars {
  private val state = MutableStateFlow<SnackbarItem?>(null)
  val current: StateFlow<SnackbarItem?> = state.asStateFlow()

  private var nextId = 0L

  fun show(text: String, action: SnackbarAction? = null) {
    nextId += 1
    state.value = SnackbarItem(nextId, text, action)
  }

  fun hide() {
    state.value = null
  }
}

const val NOT_AVAILABLE_MESSAGE = "本版本未开放"

private val BOTTOM_OFFSET = 92.dp

private const val AUTO_DISMISS_MS = 4000L

private const val AUTO_DISMISS_ACTION_MS = 8000L

private fun autoDismissMs(hasAction: Boolean): Long =
  if (hasAction) AUTO_DISMISS_ACTION_MS else AUTO_DISMISS_MS

private val SNACK_BG_LIGHT = Color(0xFF33322C)
private val SNACK_BG_DARK = Color(0xFF3A3A36)
private val SNACK_FG = Color(0xFFF4F1E8)
private val SNACK_ACTION = Color(0xFF8FD8C9)

@Composable
fun SnackbarHost(modifier: Modifier = Modifier) {
  val current by Snackbars.current.collectAsStateWithLifecycle()
  var retained by remember { mutableStateOf<SnackbarItem?>(null) }
  LaunchedEffect(current) { if (current != null) retained = current }
  val visibility = rememberVisibilityTransition(current != null)
  val item = current ?: retained ?: return
  if (!visibility.currentState && !visibility.targetState && !visibility.isRunning) return
  val dark = isSystemInDarkTheme()

  LaunchedEffect(current?.id) {
    val active = current ?: return@LaunchedEffect
    delay(autoDismissMs(active.action != null))
    if (Snackbars.current.value?.id == active.id) Snackbars.hide()
  }
  val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val progress by visibility.animateFloat(
    transitionSpec = { tween(Motion.DURATION_PANEL, easing = Motion.easeStandard) },
    label = "snackbar",
  ) { if (it) 1f else 0f }
  val rise = with(LocalDensity.current) { Motion.RISE_OFFSET.dp.toPx() }

  Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
      modifier = Modifier
        .padding(horizontal = Spacing.lg)
        .padding(bottom = BOTTOM_OFFSET + navBar)
        .fillMaxWidth()
        .guardExitingOverlay(current != null)
        .graphicsLayer {
          translationY = (1f - progress) * rise
          scaleX = Motion.POP_SCALE + (1f - Motion.POP_SCALE) * progress
          scaleY = scaleX
        }
        .alpha(progress)
        .shadow(Elevation.level2, RoundedCornerShape(Radius.lg))
        .clip(RoundedCornerShape(Radius.lg))
        .background(if (dark) SNACK_BG_DARK else SNACK_BG_LIGHT)
        .padding(vertical = Spacing.row, horizontal = Spacing.lg),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      MotionTextSwap(item.text, Modifier.weight(1f)) { text ->
        Text(
          text = text,
          style = TextStyle(fontSize = Typo.notice.size, lineHeight = 18.9.sp, color = SNACK_FG),
        )
      }
      val action = item.action
      if (action != null) {
        Text(
          text = action.label,
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .clickable(onClickLabel = action.label) {
              Snackbars.hide()
              action.onClick()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
          style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SNACK_ACTION),
        )
      }
    }
  }
}

@Composable
fun rememberToaster(): (String) -> Unit {
  val context = LocalContext.current
  return remember(context) { { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}
