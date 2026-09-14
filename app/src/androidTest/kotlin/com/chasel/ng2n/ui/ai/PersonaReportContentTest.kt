package com.chasel.ng2n.ui.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PersonaReportContentTest {
  @get:Rule val compose = createComposeRule()
  @Test fun drawersSignalTimelineBoundariesAndSourceCoordinates() {
    // s7 模拟 read_floor(tid, pid=0) 补读回来的主楼，编号在初始样本之后。
    val sources = (1..4).map { AiSource("s$it", 42, it.toLong(), it.toLong(), 1, "样例用户", "2026-09-0$it", "明确发言 $it", emptyList(), "floor") } +
      AiSource("s7", 42, 0, 0, 1, "样例用户", "2026-09-05", "补读到的主楼正文", emptyList(), "floor")
    val report = PersonaReport("本报告仅依据 4 条文本样本。", listOf("s1", "s2", "s3", "s4"),
      listOf(PersonaTendency("关注使用成本", "讨论使用成本的发言有", listOf("s1", "s2", "s7"), listOf("s3"),
        mentions = 3, bodyTail = "，多以自己的开支为例[[s2]]。")),
      listOf(PersonaTendency("反对超前消费", "明确表达这一立场的只有", listOf("s4"))),
      "侧重可验证的使用成本。", listOf(PersonaChange("2026-09", "条件变化，不能断言立场转变。", listOf("s3"))),
      "没有足够信息判断个人身份。")
    var selected: AiSource? = null
    compose.setContent { Ng2nTheme { Column(Modifier.fillMaxSize().background(LocalNg2nColors.current.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
      PersonaReportContent(report, sources, 6) { selected = it }
    } } }
    compose.onNodeWithText("已处理 4 条 · 未处理 2 条").assertIsDisplayed()
    compose.onNodeWithText("讨论使用成本的发言有3 条，多以自己的开支为例[s2]。").assertIsDisplayed()
    compose.onNodeWithText("明确表达这一立场的只有1 条").assertIsDisplayed()
    compose.onNodeWithText("多次出现").assertIsDisplayed()
    compose.onNodeWithText("偶尔提及").performScrollTo().assertIsDisplayed()
    screenshot("top")
    compose.onAllNodesWithText("查看依据")[0].performScrollTo().performClick()
    compose.onNodeWithText("代表性发言").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("「明确发言 1」").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(42L, selected?.tid); assertEquals(1L, selected?.pid) }
    compose.onNodeWithText("「补读到的主楼正文」").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("主楼").performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText("回复").assertCountEquals(2)
    compose.onAllNodesWithText("收起依据").assertCountEquals(1)
    compose.onNodeWithText("相反表述 1").performScrollTo().performClick()
    compose.onNodeWithText("「明确发言 3」").performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText("查看依据")[1].performScrollTo().performClick()
    compose.onNodeWithText("已处理样本中未找到相反表述，不代表不存在。").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("稳定性与变化").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(PERSONA_BOUNDARY).performScrollTo().assertIsDisplayed()
    screenshot("boundary")
  }
  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.filesDir, "ai-persona-$name.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
