package com.chasel.ng2n.ui.ai

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.ai.ToolCallRow
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class ToolCallRowsTest {
  @get:Rule val compose = createComposeRule()
  @Test fun failuresExpandAndUserChoiceSurvivesAnsweringInBothThemes() {
    val answering = mutableStateOf(false)
    val dark = mutableStateOf(false)
    val rows = listOf(ToolCallRow("1", "read_topic_page", "第 2 页", "ok", "已读第 2 页 · 屏蔽 1 条"),
      ToolCallRow("2", "read_floor", "pid=123", "permission_denied", "当前账号无法查看"),
      ToolCallRow("3", "read_image", "s1:image1", "ok", "图片 1 张"))
    compose.setContent { Ng2nTheme(darkTheme = dark.value) { Surface(color = LocalNg2nColors.current.bg) { ToolCallRows(rows, answering.value) } } }
    compose.onNodeWithText("3 次工具调用，1 次失败", substring = true).assertIsDisplayed()
    compose.onNodeWithText("定位楼层").performClick()
    compose.onNodeWithText("当前账号无法查看", substring = true).assertIsDisplayed()
    screenshot("ai-tools-light")
    compose.onNodeWithText("3 次工具调用，1 次失败", substring = true).performClick()
    compose.onNodeWithText("定位楼层").assertDoesNotExist()
    compose.onNodeWithText("3 次工具调用，1 次失败", substring = true).performClick()
    compose.runOnIdle { answering.value = true; dark.value = true }
    compose.onNodeWithText("定位楼层").assertIsDisplayed()
    compose.onNodeWithText("定位楼层").performClick()
    screenshot("ai-tools-dark")
  }
  @Test fun untouchedGroupCollapsesWhenAnswerStarts() {
    val answering = mutableStateOf(false)
    compose.setContent { Ng2nTheme { ToolCallRows(listOf(ToolCallRow("1", "read_topic_page", "第 2 页", "ok")), answering.value) } }
    compose.onNodeWithText("读取主题页").assertIsDisplayed()
    compose.runOnIdle { answering.value = true }
    compose.onNodeWithText("读取主题页").assertDoesNotExist()
  }
  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.getExternalFilesDir(null), "$name.png")
    file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
  }
}
