package com.chasel.ng2n.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.settings.AppSettings
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.data.settings.ThemeMode
import com.chasel.ng2n.data.settings.ThemeStyle
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.Ng2nTheme
import com.chasel.ng2n.ui.theme.TextScale

val LocalAppSettings = staticCompositionLocalOf { DEFAULT_SETTINGS }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
  ThemeMode.SYSTEM -> systemDark
  ThemeMode.DARK -> true
  ThemeMode.LIGHT -> false
}

@Composable
fun Ng2nAppTheme(content: @Composable () -> Unit) {
  val deps = rememberAppDeps()
  val settings: AppSettings by deps.settings.settings.collectAsStateWithLifecycle(DEFAULT_SETTINGS)
  val mode: ThemeMode by deps.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
  val dark = resolveDark(mode, isSystemInDarkTheme())

  CompositionLocalProvider(
    LocalAppSettings provides settings,
    LocalThemeMode provides mode,
  ) {
    Ng2nTheme(
      darkTheme = dark,
      plain = settings.themeStyle == ThemeStyle.PLAIN,
      textScale = TextScale(
        bodyFontSize = settings.appearance.bodyFontSize.toFloat(),
        bodyLineHeight = settings.appearance.bodyLineHeight.toFloat(),
        smileyScale = settings.appearance.smileyScale.toInt(),
      ),
      content = content,
    )
  }
}

@Composable
fun KeepScreenOn(enabled: Boolean = LocalAppSettings.current.keepScreenOn) {
  val context = LocalContext.current
  DisposableEffect(enabled, context) {
    val window = context.findActivity()?.window
    if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
