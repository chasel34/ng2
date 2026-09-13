package com.chasel.ng2n.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.MainActivity
import java.io.File
import org.junit.Rule
import org.junit.Test

class AppMotionSmokeTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun homeDrawerSettingsDialogAndBack() {
    compose.onNodeWithContentDescription("打开抽屉").assertIsDisplayed().performClick()
    compose.onNodeWithText("设置").performScrollTo().performClick()
    compose.onNodeWithText("夜间模式").assertIsDisplayed()
    capture("app-settings")
    compose.onNodeWithText("主题风格").performClick()
    compose.onNodeWithText("取消").assertIsDisplayed()
    capture("app-theme-dialog")
    compose.onNodeWithText("取消").performClick()
    compose.onNodeWithContentDescription("返回").performClick()
    compose.onNodeWithContentDescription("打开抽屉").assertIsDisplayed()
    capture("app-home-returned")
  }

  private fun capture(name: String) {
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "motion-verification")
    dir.mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
