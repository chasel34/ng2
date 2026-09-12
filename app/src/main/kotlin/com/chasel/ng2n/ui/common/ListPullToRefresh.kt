package com.chasel.ng2n.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration

@Composable
fun rememberListPullToRefreshState(): PullToRefreshState = remember { ListPullToRefreshState() }

@Stable
internal class ListPullToRefreshState : PullToRefreshState {

  private val anim = Animatable(0f)

  override val distanceFraction: Float get() = anim.value

  override val isAnimating: Boolean get() = anim.isRunning

  override suspend fun animateToThreshold() {
    anim.animateTo(1f)
  }

  override suspend fun animateToHidden() {
    if (!pullToRefreshNeedsHide(anim.value, anim.isRunning)) return
    anim.animateTo(0f)
  }

  override suspend fun snapTo(targetValue: Float) {
    if (!pullToRefreshNeedsSnap(anim.value, targetValue, anim.isRunning)) return
    anim.snapTo(targetValue)
  }
}

fun pullToRefreshNeedsHide(distanceFraction: Float, animating: Boolean): Boolean =
  distanceFraction != 0f || animating

fun pullToRefreshNeedsSnap(current: Float, target: Float, animating: Boolean): Boolean =
  current != target || animating

enum class PullGate {
  UNDECIDED,

  ALLOW,

  BLOCK,
}

fun pullGateDecision(dy: Float, slop: Float): PullGate = when {
  dy < -slop -> PullGate.BLOCK
  dy > slop -> PullGate.ALLOW
  else -> PullGate.UNDECIDED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPullToRefreshBox(
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val state = rememberListPullToRefreshState()
  var armed by remember { mutableStateOf(true) }
  val slop = LocalViewConfiguration.current.touchSlop
  Box(
    modifier
      .pointerInput(slop) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          armed = true
          var gate = PullGate.UNDECIDED
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (gate == PullGate.UNDECIDED) {
              val change = event.changes.firstOrNull { it.id == down.id } ?: continue
              gate = pullGateDecision(change.position.y - down.position.y, slop)
              if (gate == PullGate.BLOCK) armed = false
            }
          }
        }
      }
      .pullToRefresh(
        isRefreshing = isRefreshing,
        state = state,
        enabled = enabled && armed,
        onRefresh = onRefresh,
      ),
  ) {
    content()
    PullToRefreshDefaults.Indicator(
      modifier = Modifier.align(Alignment.TopCenter),
      isRefreshing = isRefreshing,
      state = state,
    )
  }
}
