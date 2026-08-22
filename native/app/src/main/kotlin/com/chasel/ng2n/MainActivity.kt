package com.chasel.ng2n

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chasel.ng2n.ui.Ng2nApp
import com.chasel.ng2n.ui.theme.Ng2nTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // 120Hz 设备上避免窗口被系统按 60Hz 内容源处理。
    // post 到 decorView:attributes 要在窗口已经 attach 之后写才稳。
    window.decorView.post { preferHighestRefreshRate() }

    setContent {
      Ng2nTheme {
        Ng2nApp()
      }
    }
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
