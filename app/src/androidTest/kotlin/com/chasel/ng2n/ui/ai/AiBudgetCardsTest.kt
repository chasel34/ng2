package com.chasel.ng2n.ui.ai

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiBudgetCardsTest {
  @get:Rule val compose = createComposeRule()
  @Test fun confirmationRequiresExplicitChoiceAndConfirmAndCanReopen() {
    val turn = mutableStateOf(AiTurn("概览", text = "已保留回答", analysisId = "a", card = "budget", addition = 50_000))
    val dark = mutableStateOf(false)
    var choice: Boolean? = null
    compose.setContent { Ng2nTheme(darkTheme = dark.value) {
      AiBudgetCard(turn.value, 4, { more -> choice = more; turn.value = turn.value.copy(card = null, decision = if (more) "已追加 US$0.0500 额度" else "已到此为止，结果已保存") }, {})
    } }
    compose.onNodeWithText("确定").assertIsNotEnabled()
    compose.onNodeWithText("关闭").performClick()
    compose.onNodeWithText("已达到本次额度 · 待确认").performClick()
    compose.onNodeWithText("追加 US$0.0500 额度继续").performClick()
    compose.runOnIdle { assertNull(choice) }
    screenshot("budget-light")
    compose.runOnIdle { dark.value = true }
    screenshot("budget-dark")
    compose.onNodeWithText("确定").performClick()
    compose.runOnIdle { assertEquals(true, choice) }
    compose.onNodeWithText("✓ 已追加 US$0.0500 额度").assertIsDisplayed()
  }
  @Test fun dailyCardRequiresSettings() {
    var settings = false
    compose.setContent { Ng2nTheme { AiBudgetCard(AiTurn("概览", status = "已达到今日额度", card = "daily"), 4, {}, { settings = true }) } }
    compose.onNodeWithText("修改额度").performClick()
    compose.runOnIdle { assertTrue(settings) }
    screenshot("daily-limit")
  }
  @Test fun usageShowsPendingReservationAndExpandableTokenDetails() {
    val request = com.chasel.ng2n.core.ai.AiBudgetRequest("request", "analysis", "conversation", "2026-09-14", 10_000, com.chasel.ng2n.core.ai.AiPrice(), status = "pending_verification")
    compose.setContent { Ng2nTheme { AiUsageDetails(listOf(request), "今日用量", dailyLimit = 50_000) } }
    compose.onNodeWithText("今日用量 · ≈ US$0.0100").performClick()
    compose.onNodeWithText("模型请求").assertIsDisplayed()
    compose.onNodeWithText("缓存命中输入").assertIsDisplayed()
    compose.onNodeWithText("每日 US$0.0500 · 20%（含未结预留）").assertIsDisplayed()
    compose.onNodeWithText("1 次请求用量待核实", substring = true).assertIsDisplayed()
    screenshot("usage-pending")
  }
  @Test fun settingsCorrectionsRequireManualContinueForEachRecoverableCard() {
    val card = mutableStateOf("daily")
    val settings = mutableStateOf(false)
    var continued = 0
    compose.setContent { Ng2nTheme {
      if (settings.value) androidx.compose.material3.TextButton(onClick = { settings.value = false }) { androidx.compose.material3.Text("保存设置并返回") }
      else TopicAiSheetContent(TopicAiState(visible = true, position = com.chasel.ng2n.core.ai.AiSheetPosition.FULL,
        turns = listOf(AiTurn("原问题", incomplete = true, card = card.value, analysisId = "original"))),
        {}, {}, {}, {}, { continued++ }, { settings.value = true }, {})
    } }
    listOf("daily", "AUTH", "BALANCE").forEachIndexed { index, kind ->
      compose.runOnIdle { card.value = kind }
      compose.onNodeWithText(if (kind == "daily") "修改额度" else "去设置").performScrollTo().performClick()
      compose.onNodeWithText("保存设置并返回").performClick()
      compose.runOnIdle { assertEquals(index, continued) }
      compose.onNodeWithText("继续").performScrollTo().performClick()
      compose.runOnIdle { assertEquals(index + 1, continued) }
    }
  }
  private fun screenshot(name: String) {
    val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ai-09").apply { mkdirs() }
    File(root, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
