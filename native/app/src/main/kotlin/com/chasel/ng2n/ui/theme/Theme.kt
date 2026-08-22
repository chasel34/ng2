package com.chasel.ng2n.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// 骨架期只锁两个「不能闪」的量:底色与前景色,与 res/values*/colors.xml 的
// window_background 严格一致。完整设计 token 是票 17 的活,别在这里提前发挥。
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

/**
 * 票 11 追加:设计 token(`Tokens.kt`)与字号滑块经 CompositionLocal 下发。
 *
 * Material3 的 colorScheme 仍然保留 —— 骨架期锁的那两个「不能闪」的量还归它,
 * Compose 自带控件(Slider、Switch)也从它取色。设计稿自己的 24 档色走
 * [LocalNg2nColors];两者的浅深切换由同一个 [darkTheme] 驱动,不会各说各话。
 *
 * `plain`(白底风格)与 [TextScale] 的真实取值都来自设置(票 17 接 DataStore),
 * 这里先给默认值。
 */
@Composable
fun Ng2nTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  plain: Boolean = false,
  textScale: TextScale = TextScale(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalNg2nColors provides paletteOf(dark = darkTheme, plain = plain),
    LocalNg2nTitleColors provides titleColorsOf(dark = darkTheme),
    LocalTextScale provides textScale,
  ) {
    MaterialTheme(
      colorScheme = if (darkTheme) M3DarkScheme else M3LightScheme,
      content = content,
    )
  }
}
