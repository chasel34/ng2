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
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)

    DeepLinkInbox.offer(intent)

    window.decorView.post { preferHighestRefreshRate() }

    setContent {
      if (PerfFlags.BLANK_WINDOW_BACKGROUND_AFTER_FIRST_FRAME) {
        LaunchedEffect(Unit) {
          withFrameNanos {}
          withFrameNanos {}
          window.setBackgroundDrawable(null)
        }
      }

      Ng2nAppTheme {
        Ng2nApp()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    DeepLinkInbox.offer(intent)
  }

  override fun onResume() {
    super.onResume()
    preferHighestRefreshRate()
  }

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
