package com.chasel.ng2n.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.rowClickable(
  onClickLabel: String? = null,
  enabled: Boolean = true,
  onLongClick: (() -> Unit)? = null,
  onLongClickLabel: String? = null,
  onClick: () -> Unit,
): Modifier = this
  .cancelTapOnHorizontalDrag(enabled)
  .then(
    if (onLongClick == null) {
      Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
    } else {
      Modifier.combinedClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
      )
    },
  )

fun Modifier.cancelTapOnHorizontalDrag(enabled: Boolean = true): Modifier =
  if (!enabled) this else pointerInput(Unit) {
    val slopPx = viewConfiguration.touchSlop
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      var claimed = false
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (claimed) {
          change.consume()
          if (!change.pressed) break
          continue
        }
        if (change.isConsumed) break
        if (!change.pressed) break
        val settled = awaitPointerEvent(PointerEventPass.Final)
        val fin = settled.changes.firstOrNull { it.id == down.id } ?: break
        if (!fin.pressed) break
        when (rowTapStep(fin.isConsumed, fin.position.x - down.position.x, fin.position.y - down.position.y, slopPx)) {
          RowTapStep.YIELD -> break
          RowTapStep.CLAIM -> { claimed = true; fin.consume() }
          RowTapStep.WATCH -> Unit
        }
      }
    }
  }

enum class RowTapStep {
  WATCH,

  YIELD,

  CLAIM,
}

fun rowTapStep(consumedByOthers: Boolean, dx: Float, dy: Float, slopPx: Float): RowTapStep = when {
  consumedByOthers -> RowTapStep.YIELD
  shouldCancelRowTap(dx, dy, slopPx) -> RowTapStep.CLAIM
  else -> RowTapStep.WATCH
}

fun shouldCancelRowTap(dx: Float, dy: Float, slopPx: Float): Boolean =
  abs(dx) > slopPx && abs(dx) > abs(dy)
