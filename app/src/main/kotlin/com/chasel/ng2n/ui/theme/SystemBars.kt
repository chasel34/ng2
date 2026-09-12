package com.chasel.ng2n.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.pow

const val LIGHT_SURFACE_LUMINANCE: Float = 0.179f

fun relativeLuminance(color: Color): Float {
  fun linear(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
  return 0.2126f * linear(color.red) +
    0.7152f * linear(color.green) +
    0.0722f * linear(color.blue)
}

fun isLightSurface(color: Color): Boolean = relativeLuminance(color) > LIGHT_SURFACE_LUMINANCE

fun appearanceLightStatusBarsFor(topbar: Color): Boolean = isLightSurface(topbar)

@Composable
fun StatusBarIconsEffect(topbar: Color) {
  val view = LocalView.current
  if (view.isInEditMode) return
  val appearanceLight = appearanceLightStatusBarsFor(topbar)
  SideEffect {
    val window = view.context.findActivity()?.window ?: return@SideEffect
    WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = appearanceLight
  }
}

private fun Context.findActivity(): Activity? {
  var context: Context? = this
  while (context is ContextWrapper) {
    if (context is Activity) return context
    context = context.baseContext
  }
  return null
}
