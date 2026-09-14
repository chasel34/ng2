package com.chasel.ng2n.ui.ai

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.chasel.ng2n.core.ai.AiSource
import com.chasel.ng2n.data.ai.AiSourcePreview
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiSourcePreviewSheetTest {
  @get:Rule val compose = createComposeRule()

  @Test fun variantsImagesAndPersonalPolicy() {
    val state = mutableStateOf(AiSourcePreview("ok", "主题", "作者", 3, 1, "原文", "[b]原文[/b]", images = listOf("one", "two", "three")))
    val personal = mutableStateOf(false)
    val dark = mutableStateOf(false)
    var jump = false
    var accounts = false
    var image = -1
    compose.setContent { Ng2nTheme(darkTheme = dark.value) {
      AiSourcePreviewSheet(AiSource("s2", 10, 20, 3, 1, "旧作者", "", "旧正文", emptyList()), state.value,
        !personal.value, setOf("one"), {}, { jump = true }, { accounts = true }, {}, { _, index -> image = index })
    } }
    compose.onNodeWithText("刚刚重新读取").assertIsDisplayed()
    compose.onNodeWithText("旧正文").assertDoesNotExist()
    screenshot("normal")
    compose.runOnIdle { dark.value = true }
    screenshot("normal-dark")
    compose.runOnIdle { dark.value = false }
    compose.onNodeWithText("图 1 已带入上下文，另 2 张未读取").assertIsDisplayed()
    compose.onNodeWithText("查看图 2 · 未读取").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, image) }
    compose.onNodeWithText("跳到 3 楼").performClick()
    compose.runOnIdle { assertTrue(jump); personal.value = true }
    compose.onNodeWithText("查看图 2 · 未读取").assertDoesNotExist()
    compose.runOnIdle { state.value = AiSourcePreview("unavailable") }
    compose.onNodeWithText("已删除或不可访问").assertIsDisplayed()
    screenshot("deleted")
    compose.onNodeWithText("跳到 3 楼").assertIsNotEnabled()
    compose.runOnIdle { state.value = AiSourcePreview("permission_denied") }
    compose.onNodeWithText("当前账号无法查看").assertIsDisplayed()
    screenshot("permission")
    compose.onNodeWithText("切换账号").performClick()
    compose.runOnIdle { assertTrue(accounts) }
  }
  @Test fun readerBackedNoteAndLegacyRangeShowDistinctBodies() {
    val note = com.chasel.ng2n.core.api.Floor(authorId = 2, authorKey = "2", content = "贴条原句")
    val blocked = note.copy(authorId = 3, authorKey = "3", content = "屏蔽内容")
    val detail = com.chasel.ng2n.core.api.TopicDetail(tid = 10, subject = "主题", attachBase = "", totalPages = 1,
      floors = listOf(com.chasel.ng2n.core.api.Floor(pid = 20, lou = 3, authorId = 1, authorKey = "1", content = "父楼原句", notes = listOf(note, blocked))),
      users = mapOf("1" to com.chasel.ng2n.core.api.FloorUser(key = "1", name = "父楼作者", rawName = "父楼作者"), "2" to com.chasel.ng2n.core.api.FloorUser(key = "2", name = "贴条作者", rawName = "贴条作者")))
    val source = com.chasel.ng2n.core.ai.buildTopicContext(detail, detail).sources.first { it.part == com.chasel.ng2n.core.ai.noteSourcePart(note) }
    val reader = com.chasel.ng2n.data.ai.AiSourcePreviewReader({ detail }, {
      listOf(com.chasel.ng2n.core.local.FilterRule("block", com.chasel.ng2n.core.local.FilterRuleKind.KEYWORD, com.chasel.ng2n.core.local.FilterRuleOrigin.LOCAL, "屏蔽", false))
    })
    val coordinate = com.chasel.ng2n.data.ai.AiSourceCoordinate(10, 20, 1, source.part)
    val preview = mutableStateOf(kotlinx.coroutines.runBlocking { reader.read(coordinate) })
    compose.setContent { Ng2nTheme {
      AiSourcePreviewSheet(source, preview.value, true, emptySet(), {}, {}, {}, {}, { _, _ -> })
    } }
    compose.onNodeWithText("3 楼 · 贴条 · 贴条作者").assertIsDisplayed()
    compose.onNodeWithText("贴条原句").assertIsDisplayed()
    compose.onNodeWithText("父楼原句").assertDoesNotExist()
    screenshot("note")
    val legacy = kotlinx.coroutines.runBlocking { reader.read(coordinate.copy(part = null)) }
    compose.runOnIdle { preview.value = legacy }
    compose.onNodeWithText("当前范围已重读").assertIsDisplayed()
    compose.onNodeWithText("刚刚重新读取").assertDoesNotExist()
    compose.onNodeWithText("旧引用缺少精确定位信息", substring = true).assertIsDisplayed()
    compose.onNodeWithText("父楼原句").assertIsDisplayed()
    compose.onNodeWithText("贴条原句").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("该贴条已被屏蔽，未展示正文。").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("屏蔽内容").assertDoesNotExist()
    screenshot("legacy-notes")
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val bitmap = compose.onNodeWithTag("ai-source-preview").captureToImage().asAndroidBitmap()
    java.io.File(instrumentation.targetContext.filesDir, "ai-source-$name.png").outputStream().use {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
  }
}
