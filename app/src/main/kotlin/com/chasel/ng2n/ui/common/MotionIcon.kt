package com.chasel.ng2n.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon

@Composable
fun MotionIcon(icon: Ng2nIcon, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
  AnimatedContent(
    targetState = icon,
    modifier = modifier,
    transitionSpec = {
      (fadeIn(tween(Motion.DURATION_BASE)) + scaleIn(tween(Motion.DURATION_BASE), initialScale = 0.8f)) togetherWith
        (fadeOut(tween(Motion.DURATION_EXIT)) + scaleOut(tween(Motion.DURATION_EXIT), targetScale = 0.8f))
    },
    label = "icon-swap",
  ) { AppIcon(it, tint, size) }
}
