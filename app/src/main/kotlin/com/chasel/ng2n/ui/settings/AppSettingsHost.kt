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

/**
 * 设置表在 UI 层的下发点。
 *
 * ## 为什么是 CompositionLocal
 *
 * 十几项设置里有一半是「某一屏的某一处要读一下」(左手模式挪 FAB、纯色背景换底、
 * 签名档显不显示、自动翻下一页……)。逐层传参会让每个屏幕的签名都挂上一串开关;
 * 而设置本来就是**进程级只有一份**的东西 —— 这正是 CompositionLocal 的用例
 * (票 11 的 `LocalTextScale` 已经开了这个头,这里只是把它的来源从默认值换成真设置)。
 *
 * ## 立即生效
 *
 * 整棵树读的是同一个 `StateFlow`,DataStore 一写完就重组 —— 没有「保存」这一步,
 * 也没有重启 Activity 这一步(照抄 RN 版:改一项立刻见效)。
 * 域名与 Web 反解档位不走这里:反封锁链**每请求现读**
 * (`data/net/SettingsNetworkSource`),改完下一个请求就发到新域名。
 */
val LocalAppSettings = staticCompositionLocalOf { DEFAULT_SETTINGS }

/** 夜间模式档位。跟 [LocalAppSettings] 分开住,与存储层一致(它自己一个 key)。 */
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

/**
 * 把「模式 + 系统深浅色」合成最终配色 —— 直译 RN 侧 `store/theme.ts` 的
 * `resolveColorScheme`:系统色未知时按浅色走。
 */
fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
  ThemeMode.SYSTEM -> systemDark
  ThemeMode.DARK -> true
  ThemeMode.LIGHT -> false
}

/**
 * 主题 + 设置的宿主。`MainActivity` 用它替掉裸的 [Ng2nTheme]:
 * 夜间模式、主题风格(ink/plain)、三根字号滑杆的真实取值都在这里接上 DataStore。
 *
 * 冷启动首帧读到的是 [DEFAULT_SETTINGS](DataStore 是异步的,P2-04 不许同步读盘),
 * 存档到位后重组一次。默认值与 `res/values…/colors.xml` 的窗口底色一致,所以那一次
 * 重组换不出可见的闪烁 —— 除非用户开了夜间模式而系统是浅色,那一档 RN 版同样会闪。
 */
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

/**
 * 「阅读时常亮」:挂在**主题详情屏**上(RN 版 `keepScreenOn` 的语义就是「看帖子详情时
 * 屏幕不自动熄灭」,不是全 app 常亮)。屏幕退场即撤销。
 *
 * 票 13 的主题详情屏在自己的根上调一次 `KeepScreenOn()` 即可 —— 参数默认从
 * [LocalAppSettings] 取,那边不需要知道设置项叫什么。
 */
@Composable
fun KeepScreenOn(enabled: Boolean = LocalAppSettings.current.keepScreenOn) {
  val context = LocalContext.current
  DisposableEffect(enabled, context) {
    val window = context.findActivity()?.window
    if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }
}

/** `LocalContext` 在 Activity 里拿到的通常是层 `ContextWrapper`,得剥到 Activity 为止。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
