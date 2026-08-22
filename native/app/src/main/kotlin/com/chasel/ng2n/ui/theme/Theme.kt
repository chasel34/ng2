package com.chasel.ng2n.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 骨架期只锁两个「不能闪」的量:底色与前景色,与 res/values*/colors.xml 的
// window_background 严格一致。完整设计 token 是票 17 的活,别在这里提前发挥。
private val BrandCream = Color(0xFFFCF4E1)
private val BrandInk = Color(0xFF1C1C1B)

private val LightColors = lightColorScheme(
  background = BrandCream,
  surface = BrandCream,
  onBackground = BrandInk,
  onSurface = BrandInk,
)

private val DarkColors = darkColorScheme(
  background = BrandInk,
  surface = BrandInk,
  onBackground = BrandCream,
  onSurface = BrandCream,
)

@Composable
fun Ng2nTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
