package com.chasel.ng2n.ui.common

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * 全局提示 —— 直译 RN 侧 `ui/snackbar.tsx` 与 `ui/toast.ts`。
 *
 * 分工照抄:**要带动作**(撤销 / 打开 / 去登录)或要与设计稿 1:1 的提示走 snackbar;
 * 纯气泡提示走系统 [Toast]。
 *
 * 状态放在**进程级单例**里而不是某个屏幕的 state:提示要在发起它的页面退场之后
 * 还活着(「撤销」得等得到)。
 */
data class SnackbarAction(val label: String, val onClick: () -> Unit)

data class SnackbarItem(
  /** 每次 show 递增,同文案连点两次也会重置消失计时 */
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

/** 未实现功能的统一提示文案(spec §1:入口保留,点了要给这句话)。 */
const val NOT_AVAILABLE_MESSAGE = "本版本未开放"

/** 设计稿:snack 条距底 92(给 FAB 让路)、左右 16,滑入走 omup 的 .22s。 */
private val BOTTOM_OFFSET = 92.dp

/** 自动消失时长。带撤销的提示给 4 秒反应时间。 */
private const val AUTO_DISMISS_MS = 4000L

private val SNACK_BG_LIGHT = Color(0xFF33322C)
private val SNACK_BG_DARK = Color(0xFF3A3A36)
private val SNACK_FG = Color(0xFFF4F1E8)
private val SNACK_ACTION = Color(0xFF8FD8C9)

/**
 * Snackbar 宿主。挂在 `Ng2nApp` 的最外层 —— 提示要盖在所有页面上。
 */
@Composable
fun SnackbarHost(modifier: Modifier = Modifier) {
  val current by Snackbars.current.collectAsStateWithLifecycle()
  val item = current ?: return
  val dark = isSystemInDarkTheme()

  var shown by remember(item.id) { mutableStateOf(false) }
  LaunchedEffect(item.id) {
    shown = true
    delay(AUTO_DISMISS_MS)
    Snackbars.hide()
  }
  val progress by animateFloatAsState(
    targetValue = if (shown) 1f else 0f,
    animationSpec = tween(Motion.DURATION_PANEL, easing = Motion.easeStandard),
    label = "snackbar",
  )
  val rise = with(LocalDensity.current) { Motion.RISE_OFFSET.dp.toPx() }

  Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
      modifier = Modifier
        .padding(horizontal = Spacing.lg)
        .padding(bottom = BOTTOM_OFFSET)
        .fillMaxWidth()
        .graphicsLayer { translationY = (1f - progress) * rise }
        .alpha(progress)
        .shadow(Elevation.level2, RoundedCornerShape(Radius.lg))
        .clip(RoundedCornerShape(Radius.lg))
        .background(if (dark) SNACK_BG_DARK else SNACK_BG_LIGHT)
        .padding(vertical = Spacing.row, horizontal = Spacing.lg),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
        text = item.text,
        modifier = Modifier.weight(1f),
        style = TextStyle(fontSize = Typo.notice.size, lineHeight = 18.9.sp, color = SNACK_FG),
      )
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

/** 系统气泡提示。 */
@Composable
fun rememberToaster(): (String) -> Unit {
  val context = LocalContext.current
  return remember(context) { { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}
