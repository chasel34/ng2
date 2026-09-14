package com.chasel.ng2n.ui.ai

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.theme.Ng2nTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TopicAiSheetTest {
  @get:Rule val compose = createComposeRule()

  @Test fun sheetPositionsStopFollowupCitationsAndDarkTheme() {
    val source = AiSource("s1", 42, 3, 3, 1, "甲", "今天", "原文", emptyList())
    val state = mutableStateOf(TopicAiState(visible = true, title = "主题 · 最近大家在讨论什么",
      busy = true, steps = listOf(ReadingStep("第 1 页", true), ReadingStep("热门回复", true), ReadingStep("图片 0 张", true)),
      context = TopicContext(listOf(source), null, 0, listOf(1)),
      turns = listOf(AiTurn("概览", "## 主要讨论\n大家正在比较**不同观点**。[[s1]]\n- 关注证据与实际条件。", "生成回答", thoughtSeconds = 2, thinkingStarted = 1))))
    val dark = mutableStateOf(false)
    var target: AiSource? = null
    var sent = ""
    compose.setContent {
      Ng2nTheme(darkTheme = dark.value) {
        TopicAiSheetContent(state.value, { state.value = state.value.copy(position = it) },
          { state.value = state.value.copy(draft = it) }, { sent = state.value.draft },
          { state.value = state.value.copy(busy = false, turns = state.value.turns.map { it.copy(status = "已停止 · 未完成", incomplete = true) }) },
          {}, {}, { target = it })
      }
    }
    compose.onNodeWithText("全屏").performClick()
    compose.onNodeWithText("半屏").assertIsDisplayed()
    screenshot("ai-topic-full-light")
    compose.onNodeWithText("收起").performClick()
    compose.onNodeWithText("✦ AI · 生成回答").assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(AiSheetPosition.HALF, state.value.position); assertTrue(state.value.busy) }
    compose.onNodeWithText("全屏").performClick()
    compose.onNodeWithText("3 楼").performClick()
    compose.runOnIdle { assertEquals(source, target); assertEquals(AiSheetPosition.FULL, state.value.position) }
    compose.onNodeWithText("停止").performClick()
    compose.onNodeWithText("已停止 · 未完成").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("继续").assertIsDisplayed()
    compose.onNodeWithTag("ai-prompt").performTextInput("请解释分歧")
    compose.onNodeWithText("发送").performClick()
    compose.runOnIdle { assertEquals("请解释分歧", sent); dark.value = true }
    screenshot("ai-topic-full-dark")
    val dragDistance = compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .8f
    compose.onNodeWithTag("ai-sheet-handle").performTouchInput { swipe(Offset(center.x, 20f), Offset(center.x, dragDistance), 600) }
    compose.runOnIdle { assertEquals(AiSheetPosition.COLLAPSED, state.value.position) }
  }

  @Test fun missingKeyShowsSettingsAndNoBusyControl() {
    var settings = false
    compose.setContent {
      Ng2nTheme {
        TopicAiSheetContent(TopicAiState(visible = true, needsKey = true,
          turns = listOf(AiTurn("概览", status = "尚未配置 API Key，请前往 AI 设置", incomplete = true))),
          {}, {}, {}, {}, {}, { settings = true }, {})
      }
    }
    compose.onNodeWithText("前往 AI 设置").performClick()
    compose.runOnIdle { assertTrue(settings) }
    compose.onNodeWithText("停止").assertDoesNotExist()
    compose.onNodeWithText("发送").assertIsDisplayed()
    screenshot("ai-topic-missing-key")
  }

  @Test fun streamingFollowsAgainAfterUserReturnsToBottom() {
    val state = mutableStateOf(TopicAiState(visible = true, position = AiSheetPosition.FULL, busy = true,
      turns = listOf(AiTurn("概览", (1..50).joinToString("\n\n") { "第 $it 条讨论内容" }, "生成回答"))))
    compose.setContent {
      Ng2nTheme {
        TopicAiSheetContent(state.value, {}, {}, {}, {}, {}, {}, {})
      }
    }
    val chat = compose.onNodeWithTag("ai-chat-scroll")
    fun position(): Float = chat.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    fun distance(): Float {
      val range = chat.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
      return range.maxValue() - range.value()
    }
    fun append() {
      compose.runOnIdle {
        state.value = state.value.copy(turns = state.value.turns.map { turn ->
          turn.copy(text = turn.text + (1..12).joinToString("", prefix = "\n\n") { "新增内容 $it\n\n" })
        })
      }
      compose.waitForIdle()
    }
    compose.waitForIdle()
    assertEquals(0f, distance(), 1f)
    append()
    assertEquals(0f, distance(), 1f)
    chat.performTouchInput { swipeDown(durationMillis = 600) }
    compose.waitForIdle()
    assertTrue(distance() > 64f)
    val readingPosition = position()
    append()
    assertEquals(readingPosition, position(), 1f)
    repeat(20) {
      if (distance() > 1f) chat.performTouchInput { swipeUp(durationMillis = 600) }
      compose.waitForIdle()
    }
    assertEquals(0f, distance(), 1f)
    append()
    assertEquals(0f, distance(), 1f)
  }

  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.filesDir, "$name.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
  @Test fun unsavedCardKeepsTextAndRequiresSavingBeforeContinuing() {
    val state = mutableStateOf(TopicAiState(visible = true, position = AiSheetPosition.FULL,
      unsaved = true, draft = "还没有发送的问题", turns = listOf(AiTurn("问题", "已有的内存草稿", "已中断", true))))
    var saves = 0
    var continuations = 0
    compose.setContent { Ng2nTheme {
      TopicAiSheetContent(state.value, {}, {}, {}, {}, { continuations++ }, {}, {},
        onRetrySave = { saves++; state.value = state.value.copy(unsaved = false) })
    } }
    compose.onNodeWithText("已有的内存草稿").assertIsDisplayed()
    compose.onNodeWithText("对话未能保存", substring = true).assertIsDisplayed()
    compose.onNodeWithText("发送").assertIsNotEnabled()
    compose.onNodeWithText("继续").assertDoesNotExist()
    compose.onNodeWithText("重试保存").performClick()
    compose.runOnIdle { assertEquals(1, saves); assertEquals(0, continuations) }
    compose.onNodeWithText("继续").performClick()
    compose.runOnIdle { assertEquals(1, continuations) }
  }

  @Test fun zeroSampleReadFailureOffersOnlyRetryWithoutClaimingADraft() {
    val card = mutableStateOf<String?>("READ")
    val state = mutableStateOf(TopicAiState(visible = true, position = AiSheetPosition.FULL, entryKind = "个人",
      context = TopicContext(emptyList(), null, 0, emptyList(), entryKind = "个人"),
      turns = listOf(AiTurn("分析发言", status = "历史读取失败", incomplete = true, reportExpected = true))))
    compose.setContent { Ng2nTheme {
      TopicAiSheetContent(state.value.copy(turns = state.value.turns.map { it.copy(card = card.value) }), {}, {}, {}, {}, {}, {}, {})
    } }
    listOf("READ", "EMPTY").forEach { kind ->
      compose.runOnIdle { card.value = kind }
      compose.onNodeWithText("待确认处理", substring = true).assertDoesNotExist()
      compose.onNodeWithText("重试").performScrollTo().assertIsDisplayed()
    }
    // 报告轮本身仍要说明待确认处理数，去掉的只是读取失败与零样本这两种卡片下的重复说明。
    compose.runOnIdle { card.value = null }
    compose.onNodeWithText("待确认处理", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("继续").performScrollTo().assertIsDisplayed()
  }

  @Test fun limitedRoundWithoutTextDoesNotClaimADraft() {
    val text = mutableStateOf("")
    val state = mutableStateOf(TopicAiState(visible = true, position = AiSheetPosition.FULL, entryKind = "个人",
      context = TopicContext(emptyList(), null, 0, emptyList(), entryKind = "个人"),
      turns = listOf(AiTurn("分析发言", status = "达到限制", incomplete = true, reportExpected = true, card = "LIMIT"))))
    compose.setContent { Ng2nTheme {
      TopicAiSheetContent(state.value.copy(turns = state.value.turns.map { it.copy(text = text.value) }), {}, {}, {}, {}, {}, {}, {})
    } }
    compose.onNodeWithText("待确认处理", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("已有草稿保留", substring = true).assertDoesNotExist()
    compose.onNodeWithText("本轮没有生成文字", substring = true).performScrollTo().assertIsDisplayed()
    // 同一轮真的写出了可展示的文字时才提草稿。
    compose.runOnIdle { text.value = "写了一半的分析" }
    compose.onNodeWithText("已有草稿保留", substring = true).performScrollTo().assertIsDisplayed()
  }

}
