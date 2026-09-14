package com.chasel.ng2n.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.ai.settings.AiKeyState
import com.chasel.ng2n.data.ai.settings.AiSettings
import com.chasel.ng2n.data.ai.settings.AnalysisAllowance
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiSettingsScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test fun keyEditingBudgetAndDarkTheme() {
    val state = mutableStateOf(AiSettingsUiState(loaded = true))
    val dark = mutableStateOf(false)
    var savedKey: String? = null
    compose.setContent {
      Ng2nTheme(darkTheme = dark.value) {
        AiSettingsContent(state.value, {}, { key, done ->
          savedKey = key
          state.value = state.value.copy(key = AiKeyState.Saved)
          done()
        }, { value, done ->
          state.value = state.value.copy(settings = state.value.settings.copy(allowance = value))
          done()
        }, { enabled ->
          state.value = state.value.copy(settings = state.value.settings.copy(dailyEnabled = enabled))
        }, { cents, done ->
          state.value = state.value.copy(settings = state.value.settings.copy(dailyEnabled = true, dailyLimitCents = cents))
          done()
        })
      }
    }
    compose.onNodeWithText("API Key").performClick()
    compose.onNodeWithText("输入 API Key").performTextInput("sk-cancelled")
    compose.onNodeWithText("显示").performClick()
    compose.onNodeWithText("隐藏").assertIsDisplayed()
    compose.onNodeWithText("取消").performClick()
    compose.runOnIdle { assertNull(savedKey) }
    compose.onNodeWithText("API Key").performClick()
    compose.onNodeWithText("输入 API Key").performTextInput("sk-saved")
    compose.onNodeWithText("保存").performClick()
    compose.onNodeWithText("•••••••• · 加密保存在本机").assertIsDisplayed()
    compose.runOnIdle { assertEquals("sk-saved", savedKey) }
    compose.onNodeWithText("单次分析额度 · 默认").performClick()
    compose.onNodeWithText("长楼与个人分析").performClick()
    compose.onNodeWithText("应用").performClick()
    compose.runOnIdle { assertEquals(AnalysisAllowance.LONG, state.value.settings.allowance) }
    compose.onNodeWithText("每日额度").performScrollTo().performClick()
    compose.onNodeWithText("金额，最多两位小数").performTextInput("1.23")
    compose.onNodeWithText("保存").performClick()
    compose.onNodeWithText("每日上限 · US$1.23").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("每日额度").performClick()
    compose.onNodeWithText("未设每日上限").performScrollTo().assertIsDisplayed()
    compose.runOnIdle { state.value = state.value.copy(settings = AiSettings()) }
    compose.onNodeWithText("服务商").performScrollTo()
    screenshot("ai-settings-light")
    compose.runOnIdle { dark.value = true }
    compose.onNodeWithText("API Key").assertIsDisplayed()
    screenshot("ai-settings-dark")
    compose.onNodeWithText("API Key").performClick()
    screenshot("ai-settings-key-dark")
  }

  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.filesDir, "$name.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
