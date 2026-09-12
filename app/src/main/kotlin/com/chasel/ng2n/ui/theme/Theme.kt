package com.chasel.ng2n.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val BrandCream = Color(0xFFFCF4E1)
private val BrandInk = Color(0xFF1C1C1B)

private val M3LightScheme = lightColorScheme(
  background = BrandCream,
  surface = BrandCream,
  onBackground = BrandInk,
  onSurface = BrandInk,
)

private val M3DarkScheme = darkColorScheme(
  background = BrandInk,
  surface = BrandInk,
  onBackground = BrandCream,
  onSurface = BrandCream,
)

@Composable
fun Ng2nTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  plain: Boolean = false,
  textScale: TextScale = TextScale(),
  content: @Composable () -> Unit,
) {
  val palette = paletteOf(dark = darkTheme, plain = plain)
  StatusBarIconsEffect(palette.topbar)
  CompositionLocalProvider(
    LocalNg2nColors provides palette,
    LocalNg2nTitleColors provides titleColorsOf(dark = darkTheme),
    LocalTextScale provides textScale,
  ) {
    MaterialTheme(
      colorScheme = if (darkTheme) M3DarkScheme else M3LightScheme,
      content = content,
    )
  }
}
