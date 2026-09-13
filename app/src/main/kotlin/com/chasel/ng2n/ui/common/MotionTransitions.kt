package com.chasel.ng2n.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

fun pageTransition(back: Boolean = false): ContentTransform {
  val direction = if (back) -1 else 1
  return slideInHorizontally(
    tween(Motion.DURATION_PAGE, easing = Motion.easeDecelerate),
  ) { direction * it } togetherWith slideOutHorizontally(
    tween(Motion.DURATION_PAGE, easing = Motion.easeDecelerate),
  ) { -direction * it }
}

@Composable
internal fun rememberVisibilityTransition(open: Boolean): Transition<Boolean> {
  val state = remember { MutableTransitionState(false) }
  state.targetState = open
  return rememberTransition(state, label = "overlay-visibility")
}

internal fun Modifier.guardExitingOverlay(open: Boolean): Modifier = if (open) this else
  clearAndSetSemantics { }.pointerInput(Unit) {
    awaitPointerEventScope {
      while (true) {
        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
      }
    }
  }

@Composable
fun <T> MotionTextSwap(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
  AnimatedContent(
    targetState = value,
    modifier = modifier,
    transitionSpec = {
      (fadeIn(tween(Motion.DURATION_BASE)) + slideInVertically(tween(Motion.DURATION_BASE)) { it / 3 }) togetherWith
        (fadeOut(tween(Motion.DURATION_EXIT)) + slideOutVertically(tween(Motion.DURATION_BASE)) { -it / 3 })
    },
    label = "text-state-swap",
  ) { content(it) }
}
