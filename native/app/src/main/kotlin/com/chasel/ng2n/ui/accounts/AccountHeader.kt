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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

/**
 * 抽屉顶部的账号头 —— `src/ui/app-drawer.tsx` 里那一块的移植。抽屉本体归票 16,
 * 这里只出组件:票 16 直接把它放进抽屉的第一屏位置。
 *
 * ## 手势(阈值照抄 RN 版)
 *
 * - [GESTURE_SLOP] = 12dp:横向位移超过它才认,免得和抽屉的纵向滚动打架;
 * - 同时要求 `|dx| > |dy| * 1.3` —— 斜着划不算切号;
 * - 松手时 `dx ≤ -40dp` **或** 甩速 `< -0.5 dp/ms` 切下一个,反向切上一个;
 * - 循环取(到头绕回),不足两个账号没得切。
 *
 * RN 版的三个常量在 `app-drawer.tsx:20-23`(GESTURE_SLOP / SWIPE_COMMIT /
 * SWIPE_VELOCITY),单位是 RN 的「dp 式」逻辑像素与 dp/ms,与这里的 dp / (dp/ms) 同口径。
 *
 * ## 与 RN 版的一处差别
 *
 * RN 用 `PanResponder`(JS 线程判定);这里用 Compose 的 `awaitEachGesture`,
 * 判定在 UI 线程完成 —— 阈值语义一样,只是不再有跨线程延迟。
 * 头像左右两颗 chevron 照旧可点(RN 版也有),不足两个账号时它们变暗且不响应。
 */
private val GESTURE_SLOP = 12.dp
private val SWIPE_COMMIT = 40.dp

/** dp/ms。`VelocityTracker` 给的是 px/s,判定时换算过去。 */
private const val SWIPE_VELOCITY_DP_PER_MS = 0.5f

/** uiautomator 找账号头的锚点。 */
const val ACCOUNT_HEADER_TAG: String = "ng2n-account-header"

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

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.primary)
      .then(if (canCycle) Modifier.cycleOnSwipe(viewModel::cycle) else Modifier)
      .padding(horizontal = 20.dp)
      .padding(top = 22.dp, bottom = 18.dp)
      .semantics { contentDescription = ACCOUNT_HEADER_TAG },
  ) {
    if (current == null) {
      // 游客态:一个「点此登录账号」入口,没有可切的号(RN 版同样的两分支)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center,
        ) {
          PersonAddIcon(tint = MaterialTheme.colorScheme.onPrimary, size = 26.dp)
        }
      }
      Spacer(Modifier.height(14.dp))
      Text(
        text = "未登录 · 登录多个账号可少跳系统浏览器",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
      )
      Spacer(Modifier.height(3.dp))
      Text(
        text = "点此登录账号",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimary,
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
        onClick = { viewModel.cycle(-1) },
      )
      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = nameAbbrev(current.name, 4),
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      }
      CycleButton(
        pointsLeft = false,
        enabled = canCycle,
        label = "下一个账号",
        onClick = { viewModel.cycle(1) },
      )
    }
    Spacer(Modifier.height(14.dp))
    Text(
      text = "已登录 ${state.accounts.size} 个账号 · 左右滑动切换",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
    )
    Spacer(Modifier.height(3.dp))
    Text(
      text = "当前：${current.name}(${current.uid})",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .clickable(onClick = onOpenAccounts)
        .semantics { contentDescription = "打开账号管理" },
    )
  }
}

@Composable
private fun CycleButton(
  pointsLeft: Boolean,
  enabled: Boolean,
  label: String,
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
    ChevronIcon(tint = MaterialTheme.colorScheme.onPrimary, pointsLeft = pointsLeft)
  }
}

/**
 * 左右滑循环切号。阈值见文件头。
 *
 * 手势在**松手那一刻**定向(与 RN 版的 `onPanResponderRelease` 同),
 * 中途不做任何跟手位移 —— RN 版也没有,账号头是「一下切一个」而不是可拖的卡片流。
 */
private fun Modifier.cycleOnSwipe(onCycle: (Int) -> Unit): Modifier = this.pointerInput(onCycle) {
  val slopPx = GESTURE_SLOP.toPx()
  val commitPx = SWIPE_COMMIT.toPx()
  // dp/ms → px/s:×density 换到 px,×1000 换到秒
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
      // 认定条件与 RN 版 onMoveShouldSetPanResponder 一致:横向够远、且明显比纵向多
      if (!claimed && abs(dx) > slopPx && abs(dx) > abs(dy) * 1.3f) claimed = true
      // 认下了就吃掉事件,免得抽屉的纵向滚动跟着动(RN 侧由 PanResponder 抢占实现)
      if (claimed) change.consume()
    }

    if (!claimed) return@awaitEachGesture
    val vx = tracker.calculateVelocity().x
    when {
      // 左滑看下一个,右滑看上一个,循环
      dx <= -commitPx || vx < -velocityPxPerSecond -> onCycle(1)
      dx >= commitPx || vx > velocityPxPerSecond -> onCycle(-1)
    }
  }
}
