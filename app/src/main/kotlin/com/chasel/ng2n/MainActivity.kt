package com.chasel.ng2n

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.chasel.ng2n.ui.Ng2nApp
import com.chasel.ng2n.ui.nav.DeepLinkInbox
import com.chasel.ng2n.ui.perf.PerfFlags
import com.chasel.ng2n.ui.settings.Ng2nAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    // 状态栏一律**透明 + 白图标**:三套配色的顶栏都是深色(见 `ui/theme/SystemBars.kt`)。
    // 不带参的 enableEdgeToEdge() 按系统夜间模式投票,浅色档会把图标刷成黑的,
    // 压在深青顶栏上几乎看不见(票 39)。首帧就定下来,免得开屏闪一下黑图标;
    // 之后主题真变了由 `StatusBarIconsEffect` 重投。
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)

    // 冷启动深链:intent 比第一次 composition 还早,先投进收件箱,导航宿主起来后取件。
    // 「垫首页」不需要 TaskStackBuilder —— Nav3 的 back stack 恒以 Home 开局
    // (说明见 `ui/nav/DeepLinkInbox.kt`)。
    DeepLinkInbox.offer(intent)

    // 120Hz 设备上避免窗口被系统按 60Hz 内容源处理。
    // post 到 decorView:attributes 要在窗口已经 attach 之后写才稳。
    window.decorView.post { preferHighestRefreshRate() }

    setContent {
      // 票 59 二轮的**测量口子**,默认关(见 `PerfFlags`)。
      // `windowBackground` 是 DecorView 每帧一次的满屏不透明填充,Compose 侧首页
      // 自己就铺满了底色,首帧之后它理论上纯属浪费 —— 但「有没有哪一帧 Compose
      // 没铺满」只有真机验得了,赌错就是黑闪,所以做成编译期开关等真机。
      if (PerfFlags.BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME) {
        LaunchedEffect(Unit) {
          // 连等两次:`withFrameNanos` 的回调发生在**这一帧开画之前**,等到第二次
          // 回调时第一帧的 Compose 内容已经进过 DecorView 的 display list 了。
          withFrameNanos {}
          withFrameNanos {}
          window.setBackgroundDrawable(null)
        }
      }

      // 夜间模式 / 主题风格 / 三根字号滑杆都从 DataStore 现读(票 17c):
      // 设置页一改,整棵树跟着重组,不重启 Activity。
      Ng2nAppTheme {
        Ng2nApp()
      }
    }
  }

  /**
   * `launchMode=singleTask`:app 已经在前台时再点一条 `ng2n://` 链接走的是这里,
   * 不会重新 `onCreate`。
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    DeepLinkInbox.offer(intent)
  }

  override fun onResume() {
    super.onResume()
    // 厂商系统从后台恢复时会重新做窗口刷新率投票,因此前台恢复时再声明一次。
    preferHighestRefreshRate()
  }

  /**
   * 请求当前分辨率下的最高刷新率。
   *
   * 逻辑照抄 RN 版的 config plugin `plugins/with-high-refresh-rate.js`:只在同分辨率的
   * supportedModes 里挑最高刷新率,避免系统顺手把窗口切到别的分辨率档。
   */
  private fun preferHighestRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    @Suppress("DEPRECATION")
    val targetDisplay =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
      else windowManager.defaultDisplay
    val activeDisplay = targetDisplay ?: return
    val currentMode = activeDisplay.mode
    val bestMode = activeDisplay.supportedModes
      .filter {
        it.physicalWidth == currentMode.physicalWidth &&
          it.physicalHeight == currentMode.physicalHeight
      }
      .maxByOrNull { it.refreshRate }
      ?: return

    val attributes = window.attributes
    attributes.preferredDisplayModeId = bestMode.modeId
    attributes.preferredRefreshRate = bestMode.refreshRate
    window.attributes = attributes
  }
}
