package com.chasel.ng2n.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.perf.PerfFlags
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class DrawerHostState internal constructor() {

  private val progressState = mutableFloatStateOf(0f)

  val progress: Float get() = progressState.floatValue

  var isOpen: Boolean by mutableStateOf(false)
    private set

  private val animatable = Animatable(0f)
  private var animation: Job? = null

  internal fun snap(value: Float) {
    progressState.floatValue = value
  }

  internal fun beginDrag(): Float {
    animation?.cancel()
    animation = null
    return progress
  }

  internal fun settle(scope: CoroutineScope, open: Boolean) {
    isOpen = open
    animation?.cancel()
    animation = scope.launch {
      animatable.snapTo(progress)
      animatable.animateTo(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(
          durationMillis = if (open) {
            DrawerGeometry.OPEN_DURATION_MS
          } else {
            DrawerGeometry.CLOSE_DURATION_MS
          },
          easing = Motion.easeStandard,
        ),
      ) { snap(value) }
    }
  }

  fun open(scope: CoroutineScope) = settle(scope, true)

  fun close(scope: CoroutineScope) = settle(scope, false)
}

@Composable
fun rememberDrawerHostState(): DrawerHostState = remember { DrawerHostState() }

@Composable
fun DrawerHost(
  state: DrawerHostState,
  drawerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  val widthPx = with(density) { DrawerGeometry.WIDTH_DP.dp.toPx() }
  val edgePx = with(density) { DrawerGeometry.EDGE_WIDTH_DP.dp.toPx() }
  val slopPx = with(density) { DrawerGeometry.GESTURE_SLOP_DP.dp.toPx() }

  BackHandler(enabled = state.isOpen) { state.close(scope) }

  val visible by remember(state) { derivedStateOf { state.progress > 0f } }

  Box(modifier.fillMaxSize()) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithContent {
          val left = drawerScrimLeft(state.progress, widthPx, size.width)
          if (left <= 0f) {
            drawContent()
          } else {
            clipRect(left = left) { this@drawWithContent.drawContent() }
          }
        }
        .graphicsLayer()
        .drawerDrag(
          state = state,
          scope = scope,
          widthPx = widthPx,
          slopPx = slopPx,
          edgePx = edgePx,
          allowOpen = true,
          allowClose = false,
          pass = PointerEventPass.Initial,
        ),
    ) {
      content()
    }

    if (visible) {
      Box(
        Modifier
          .fillMaxSize()
          .drawBehind {
            val left = drawerScrimLeft(state.progress, widthPx, size.width)
            drawRect(
              color = colors.scrim,
              topLeft = Offset(left, 0f),
              size = Size(size.width - left, size.height),
              alpha = state.progress,
            )
          }
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "关闭抽屉",
          ) { state.close(scope) }
          .semantics { contentDescription = "关闭抽屉" },
      )
      Box(
        Modifier
          .align(Alignment.CenterStart)
          .width(DrawerGeometry.WIDTH_DP.dp)
          .fillMaxHeight()
          .graphicsLayer { translationX = -(1f - state.progress) * widthPx }
          .then(if (PerfFlags.DRAWER_PANEL_SHADOW) Modifier.shadow(Elevation.level2) else Modifier)
          .background(colors.surface)
          .drawerDrag(
            state = state,
            scope = scope,
            widthPx = widthPx,
            slopPx = slopPx,
            edgePx = null,
            allowOpen = false,
            allowClose = true,
            pass = PointerEventPass.Initial,
          ),
      ) {
        drawerContent()
      }
    }
  }
}

private fun Modifier.drawerDrag(
  state: DrawerHostState,
  scope: CoroutineScope,
  widthPx: Float,
  slopPx: Float,
  edgePx: Float?,
  allowOpen: Boolean,
  allowClose: Boolean,
  pass: PointerEventPass,
): Modifier = pointerInput(widthPx, slopPx, edgePx, allowOpen, allowClose, pass) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (edgePx != null && !isInDrawerEdge(down.position.x, edgePx)) return@awaitEachGesture

    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var startProgress = 0f
    var claimed = false
    var settled = false

    while (true) {
      val event = awaitPointerEvent(pass)
      val change = event.changes.firstOrNull { it.id == down.id } ?: break
      if (!claimed && change.isConsumed) break
      tracker.addPosition(change.uptimeMillis, change.position)

      if (!change.pressed) {
        if (claimed) {
          change.consume()
          state.snap(drawerProgress(startProgress, change.position.x - down.position.x, widthPx))
          val velocity = tracker.calculateVelocity().x / 1000f
          state.settle(scope, settleDrawerOpen(startProgress, state.progress, velocity))
          settled = true
        }
        break
      }

      val dx = change.position.x - down.position.x
      val dy = change.position.y - down.position.y
      if (!claimed) {
        if (!shouldClaimDrawerDrag(dx, dy, slopPx, allowOpen, allowClose)) continue
        claimed = true
        startProgress = state.beginDrag()
      }
      change.consume()
      state.snap(drawerProgress(startProgress, dx, widthPx))
    }

    if (claimed && !settled) state.settle(scope, state.progress >= 0.5f)
  }
}
