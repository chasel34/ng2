package com.chasel.ng2n.ui.ai

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.db.*
import com.chasel.ng2n.data.topic.TopicPageParams
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.theme.Ng2nTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import java.io.File

class AiHistoryScreenTest {
  @get:Rule val compose = createComposeRule()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val sessions get() = EntryPointAccessors.fromApplication(context.applicationContext, AiSessionsEntryPoint::class.java).aiSessions()
  private val id = "ai-history-ui-fixture"
  private val tid = 987654321L
  @Before fun seed() = runBlocking {
    sessions.store.initialize()
    sessions.store.undo(id)
    sessions.store.save(AiConversationEntity(id, "消费体感变差从什么时候开始", "主题", tid, "网事杂谈 · 用户 Alice", 1,
      System.currentTimeMillis(), "已中断", "第 1 页 · 热门回复 · 图片 1 张", Json.encodeToString(TopicPageParams(tid, 1)), null),
      listOf(AiMessageEntity(id, 0, Json.encodeToString(AiTurn("概览", "已保留的草稿", "已中断", true)))),
      listOf(AiReadingRangeEntity(id, 0, "第 1 页", true)), emptyList(), AiRunEntity("ui-run", id, "interrupted", "读取第 2 页", 1))
  }
  @After fun cleanup() = runBlocking { sessions.store.delete(id); sessions.store.confirmDelete(id); sessions.forget(id) }
  @Test fun searchMenuDeleteUndoAndDarkTheme() {
    val dark = mutableStateOf(false)
    var target: NavKey? = null
    val nav = object : Navigator { override fun push(key: NavKey) { target = key }; override fun pop() = Unit }
    compose.setContent { Ng2nTheme(darkTheme = dark.value) { AiHistoryScreen(AiHistoryKey(tid), nav) } }
    compose.onNodeWithText("消费体感变差从什么时候开始").assertIsDisplayed()
    compose.onNodeWithText("已中断").assertIsDisplayed()
    screenshot("ai-history-light")
    compose.onNodeWithText("搜索").performClick()
    compose.onNodeWithText("搜索标题、主题或用户").performTextInput("不存在")
    compose.onNodeWithText("没有找到相关对话").assertIsDisplayed()
    screenshot("ai-history-empty")
    compose.onNodeWithText("清除搜索").performClick()
    compose.onNodeWithText("搜索标题、主题或用户").performTextInput("alice")
    compose.onNodeWithText("消费体感变差从什么时候开始").assertIsDisplayed()
    compose.onNodeWithText("返回").performClick()
    compose.runOnIdle { dark.value = true }
    screenshot("ai-history-dark")
    compose.onNodeWithText("消费体感变差从什么时候开始").performTouchInput { longClick() }
    compose.onNodeWithText("继续聊天").performClick()
    compose.runOnIdle { assertEquals(AiChatKey(id), target) }
    compose.onNodeWithText("⋮").performClick()
    compose.onNodeWithText("删除对话").performClick()
    compose.onNodeWithText("已删除对话，用量记录保留").assertIsDisplayed()
    compose.onNodeWithText("撤销").performClick()
    compose.onNodeWithText("消费体感变差从什么时候开始").assertIsDisplayed()
  }
  @Test fun historyOpensFullScreenWithInterruptionAndNoAutomaticRun() {
    val nav = object : Navigator { override fun push(key: NavKey) = Unit; override fun pop() = Unit }
    compose.setContent { Ng2nTheme { AiChatScreen(AiChatKey(id), nav) } }
    compose.waitUntil(5_000) { sessions.open(id).state.value.visible }
    compose.onNodeWithText("✦ AI 对话").assertIsDisplayed()
    compose.onNodeWithText("已保留的草稿").assertIsDisplayed()
    compose.onNodeWithText("上次运行已中断", substring = true).assertIsDisplayed()
    compose.onNodeWithText("继续", useUnmergedTree = true).assertIsDisplayed()
    compose.runOnIdle { assertFalse(sessions.open(id).state.value.busy) }
    screenshot("ai-history-interrupted")
  }
  private fun screenshot(name: String) {
    compose.mainClock.advanceTimeBy(300)
    compose.waitForIdle()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    Thread.sleep(300)
    val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    val dir = File(context.getExternalFilesDir(null), "ai-07").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
