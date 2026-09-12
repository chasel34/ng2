package com.chasel.ng2n.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

object Motion {

  val easeStandard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

  val easeDecelerate: Easing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)

  const val DURATION_MENU = 160

  const val DURATION_QUICK = 180

  const val DURATION_BASE = 200

  const val DURATION_PANEL = 220

  const val DURATION_NOTICE = 280

  const val RISE_OFFSET = 14f

  const val POP_SCALE = 0.94f
}
