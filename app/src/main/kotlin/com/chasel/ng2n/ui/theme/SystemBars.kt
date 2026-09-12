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

/**
 * 状态栏图标(时间 / 信号 / 电池)的明暗。
 *
 * 判据是**状态栏后面压着的那块底色**,也就是顶栏色 [Ng2nColors.topbar] —— 不是页面深浅,
 * 更不是系统的夜间模式。本 app 三套配色的顶栏都是深色(浅色档深青 `#14796B`、
 * 纯白档同色、夜间档近黑 `#1C1C1B`),所以三档都该是白图标。
 *
 * 票 39 修的就是这条:`enableEdgeToEdge()` 不带参时按**系统夜间模式**投票
 * (`SystemBarStyle.auto`),浅色档下它把图标刷成黑的,压在深青顶栏上几乎看不见。
 * RN 版没有这个歧义 —— `src/app/_layout.tsx:116` 一句 `<StatusBar style="light" />`
 * 全局钉死白图标。这里不写死是因为票 45 的登录屏之后可能出现浅顶栏,
 * 到那时这个函数自己会翻过来。
 */

/**
 * 「底色算浅色」的判据:sRGB 相对亮度超过 WCAG 的黑白对比翻转点。
 *
 * 0.179 = 解 `(L+0.05)/0.05 = 1.05/(L+0.05)` 得到的那个点 —— 亮度高于它时黑前景
 * 的对比度反超白前景。用它而不是拍脑袋的 0.5:`#14796B` 的相对亮度是 0.146,
 * 感知上明明是深色,但按「亮度 > 0.5」这类判据附近的中间色会判反。
 */
const val LIGHT_SURFACE_LUMINANCE: Float = 0.179f

/**
 * sRGB 相对亮度(WCAG 2.x 定义):先把每个通道解伽马到线性光,再按人眼加权求和。
 *
 * 不用 Compose 自带的 `Color.luminance()`,是为了让判据本身留在纯算术里、
 * JVM 单测能逐值对拍(`SystemBarsTest`)。两者算的是同一个量。
 */
fun relativeLuminance(color: Color): Float {
  fun linear(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
  return 0.2126f * linear(color.red) +
    0.7152f * linear(color.green) +
    0.0722f * linear(color.blue)
}

/** 这块底色算不算「浅」—— 浅底配深图标,深底配白图标。 */
fun isLightSurface(color: Color): Boolean = relativeLuminance(color) > LIGHT_SURFACE_LUMINANCE

/**
 * `WindowInsetsControllerCompat.isAppearanceLightStatusBars` 该取的值。
 *
 * 名字里的 "light" 指的是**状态栏背景浅**,系统据此把图标画成深色 —— 与直觉相反,
 * 所以这里包一层带语义的名字,调用点不必再想一遍。
 */
fun appearanceLightStatusBarsFor(topbar: Color): Boolean = isLightSurface(topbar)

/** 把 [appearanceLightStatusBarsFor] 的结论落到窗口上。主题一变(夜间/风格)跟着重投。 */
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

/** `LocalView` 的 context 通常是层 `ContextWrapper`,得剥到 Activity 为止。 */
private fun Context.findActivity(): Activity? {
  var context: Context? = this
  while (context is ContextWrapper) {
    if (context is Activity) return context
    context = context.baseContext
  }
  return null
}
