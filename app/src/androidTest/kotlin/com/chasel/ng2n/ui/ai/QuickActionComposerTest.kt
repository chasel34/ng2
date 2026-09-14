package com.chasel.ng2n.ui.ai

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.chasel.ng2n.core.ai.QuickAction
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuickActionComposerTest {
  @get:Rule val compose = createComposeRule()
  @Test fun slashFilterEmptyEscapeKeyboardAndBusy() {
    val state = mutableStateOf(TopicAiState())
    var selected: QuickAction? = null
    var sent = false
    compose.setContent { Ng2nTheme {
      QuickActionComposer(state.value, { state.value = state.value.copy(draft = it) }, { sent = true }, {}, {
        selected = it; state.value = state.value.copy(draft = "", busy = true)
      })
    } }
    val input = compose.onNodeWithTag("ai-prompt")
    input.performTextInput("/不存在")
    compose.onNodeWithText("没有匹配「不存在」的快捷操作").assertIsDisplayed()
    screenshot("ai-quick-empty")
    input.performKeyInput { pressKey(Key.Escape) }
    compose.onNodeWithTag("ai-quick-menu").assertDoesNotExist()
    input.performTextReplacement("/事实")
    compose.onNodeWithText("提取可核查的说法，查找来源并说明证据状态").assertIsDisplayed()
    screenshot("ai-quick-filtered")
    input.performKeyInput { pressKey(Key.Tab) }
    compose.runOnIdle { assertEquals("fact-check", selected?.skillId); assertFalse(sent) }
    compose.onNodeWithTag("ai-quick-menu").assertDoesNotExist()
    compose.onNodeWithText("事实核查").assertIsNotEnabled()
    compose.runOnIdle { state.value = state.value.copy(busy = false, floorEntry = true) }
    compose.onNodeWithText("查找前情").assertExists()
    compose.onNodeWithText("/").performClick()
    input.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
    compose.runOnIdle { assertEquals("critical-thinking", selected?.skillId) }
  }
  @Test fun slashMenuOpensInAFinishedConversationInsideTheSheet() {
    val state = mutableStateOf(TopicAiState(visible = true, title = "主题 · 讨论", conversationId = "c1",
      turns = listOf(AiTurn("概览", "回答", "已完成"))))
    val full = mutableStateOf(false)
    compose.setContent { Ng2nTheme {
      TopicAiSheetContent(state.value, { state.value = state.value.copy(position = it) },
        { state.value = state.value.copy(draft = it) }, {}, {}, {}, {}, {}, fullScreen = full.value)
    } }
    for (screen in listOf(false, true)) {
      compose.runOnIdle { full.value = screen; state.value = state.value.copy(draft = "") }
      compose.onNodeWithText("/").performClick()
      compose.onNodeWithTag("ai-quick-menu").assertIsDisplayed()
      compose.onNodeWithText("/").performClick()
      compose.onNodeWithTag("ai-quick-menu").assertDoesNotExist()
      compose.onNodeWithTag("ai-prompt").performTextInput("/")
      compose.runOnIdle { assertEquals("/", state.value.draft) }
      compose.onNodeWithTag("ai-quick-menu").assertIsDisplayed()
      compose.onNodeWithText("输入文字筛选快捷操作").assertIsDisplayed()
      compose.onNodeWithTag("ai-prompt").performKeyInput { pressKey(Key.Escape) }
    }
  }

  private fun screenshot(name: String) {
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val file = java.io.File(context.getExternalFilesDir(null), "$name.png")
    file.outputStream().use { compose.onNodeWithTag("ai-quick-menu").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
  }

}
