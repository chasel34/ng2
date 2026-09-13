package com.chasel.ng2n.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ReducedMotionInstrumentationTest {
  @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
    override val scaleFactor = 0f
  })

  @Test fun disabledAnimationsStillRemoveDialog() {
    val open = mutableStateOf(false)
    var mounted = false
    compose.setContent {
      Ng2nTheme {
        DialogShell(open.value, {}) {
          DisposableEffect(Unit) {
            mounted = true
            onDispose { mounted = false }
          }
          Text("无动画对话框")
        }
      }
    }
    compose.runOnIdle { open.value = true }
    compose.onNodeWithText("无动画对话框").assertIsDisplayed()
    compose.runOnIdle { open.value = false }
    compose.waitForIdle()
    assertFalse(mounted)
  }
}
