package com.chasel.ng2n.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.TopbarOverlay
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.abs

private val GESTURE_SLOP = 12.dp
private val SWIPE_COMMIT = 40.dp

private const val SWIPE_VELOCITY_DP_PER_MS = 0.5f

const val ACCOUNT_HEADER_TAG: String = "ng2n-account-header"

val ACCOUNT_HEADER_TOP_GAP: Dp = 22.dp

fun accountHeaderTopPadding(statusBarTop: Dp): Dp = statusBarTop + ACCOUNT_HEADER_TOP_GAP

@Composable
fun AccountHeader(
  viewModel: AccountsViewModel,
  onOpenAccounts: () -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val current = state.current()
  val canCycle = state.accounts.size >= 2
  val colors = LocalNg2nColors.current
  val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(colors.primary)
      .then(if (canCycle) Modifier.cycleOnSwipe(viewModel::cycle) else Modifier)
      .padding(horizontal = Spacing.xl)
      .padding(top = accountHeaderTopPadding(statusBarTop), bottom = 18.dp)
      .semantics { contentDescription = ACCOUNT_HEADER_TAG },
  ) {
    if (current == null) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(TopbarOverlay),
          contentAlignment = Alignment.Center,
        ) {
          PersonAddIcon(tint = colors.onPrimary, size = 26.dp)
        }
      }
      Spacer(Modifier.height(14.dp))
      Text(
        text = "未登录 · 登录多个账号可少跳系统浏览器",
        style = captionStyle(colors),
      )
      Spacer(Modifier.height(3.dp))
      Text(
        text = "点此登录账号",
        style = headlineStyle(colors),
        modifier = Modifier
          .clickable(onClick = onLogin)
          .semantics { contentDescription = "点此登录账号" },
      )
      return@Column
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      CycleButton(
        pointsLeft = true,
        enabled = canCycle,
        label = "上一个账号",
        colors = colors,
        onClick = { viewModel.cycle(-1) },
      )
      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(TopbarOverlay),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = nameAbbrev(current.name, 4),
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
          color = colors.onPrimary,
        )
      }
      CycleButton(
        pointsLeft = false,
        enabled = canCycle,
        label = "下一个账号",
        colors = colors,
        onClick = { viewModel.cycle(1) },
      )
    }
    Spacer(Modifier.height(14.dp))
    Text(
      text = "已登录 ${state.accounts.size} 个账号 · 左右滑动切换",
      style = captionStyle(colors),
    )
    Spacer(Modifier.height(3.dp))
    Text(
      text = "当前：${current.name}(${current.uid})",
      style = headlineStyle(colors),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .clickable(onClick = onOpenAccounts)
        .semantics { contentDescription = "打开账号管理" },
    )
  }
}

private fun captionStyle(colors: Ng2nColors) = TextStyle(
  fontSize = Typo.listMeta.size,
  lineHeight = Typo.listMeta.lineHeight,
  color = colors.onPrimary.copy(alpha = 0.8f),
)

private fun headlineStyle(colors: Ng2nColors) = TextStyle(
  fontSize = Typo.tab.size,
  lineHeight = Typo.tab.lineHeight,
  fontWeight = FontWeight.SemiBold,
  color = colors.onPrimary,
)

@Composable
private fun CycleButton(
  pointsLeft: Boolean,
  enabled: Boolean,
  label: String,
  colors: Ng2nColors,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(28.dp)
      .alpha(if (enabled) 0.55f else 0.2f)
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    ChevronIcon(tint = colors.onPrimary, pointsLeft = pointsLeft)
  }
}

private fun Modifier.cycleOnSwipe(onCycle: (Int) -> Unit): Modifier = this.pointerInput(onCycle) {
  val slopPx = GESTURE_SLOP.toPx()
  val commitPx = SWIPE_COMMIT.toPx()
  val velocityPxPerSecond = SWIPE_VELOCITY_DP_PER_MS * density * 1000f

  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dx = 0f
    var dy = 0f
    var claimed = false

    while (true) {
      val event = awaitPointerEvent()
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      if (!change.pressed) break
      dx = change.position.x - down.position.x
      dy = change.position.y - down.position.y
      tracker.addPosition(change.uptimeMillis, change.position)
      if (!claimed && abs(dx) > slopPx && abs(dx) > abs(dy) * 1.3f) claimed = true
      if (claimed) change.consume()
    }

    if (!claimed) return@awaitEachGesture
    val vx = tracker.calculateVelocity().x
    when {
      dx <= -commitPx || vx < -velocityPxPerSecond -> onCycle(1)
      dx >= commitPx || vx > velocityPxPerSecond -> onCycle(-1)
    }
  }
}
