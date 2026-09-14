package com.chasel.ng2n.ui.ai

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.core.api.*
import com.chasel.ng2n.core.local.*
import com.chasel.ng2n.ui.board.TopicRow
import com.chasel.ng2n.ui.board.buildTopicRows
import com.chasel.ng2n.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiEntryPanelsTest {
  @get:Rule val compose = createComposeRule()
  private fun floor(id: Long) = Floor(pid = id, lou = id, authorKey = "1", content = "正文 $id")
  private val first = TopicDetail(tid = 42, subject = "讨论", attachBase = "", floors = listOf(floor(0), floor(1), floor(2), floor(5)))

  @Test fun listShowsCountsExpandableDetailsAndItsOwnActions() {
    val context = buildListContext((1L..412).map { Topic(it, subject = "主题 $it", author = "甲") }, "网事杂谈", "最新回复")
    panel(context)
    compose.onNodeWithText("全屏").performClick()
    compose.onNodeWithText("412 个主题").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("靠前 300 个").assertIsDisplayed()
    compose.onNodeWithText("当前列表").performClick()
    compose.onNodeWithText("靠前 300 个").assertDoesNotExist()
    compose.onNodeWithText("当前列表").performClick()
    compose.onNodeWithText("正文和楼层不默认读取", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("深入某个话题").assertIsDisplayed()
    compose.onNodeWithText("梳理讨论分歧").assertIsDisplayed()
    screenshot("list")
  }

  @Test fun chainShowsUpstreamCurrentDownstreamAndArgumentAction() {
    val context = buildChainContext(first, listOf(first), listOf(
      ChainNode(1, ChainRole.UPSTREAM, true), ChainNode(2, ChainRole.CURRENT, true), ChainNode(5, ChainRole.DOWNSTREAM, true)))
    panel(context)
    compose.onNodeWithText("全屏").performClick()
    listOf("主楼正文", "上游", "当前", "下游", "5 楼").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    compose.onNodeWithText("检查各方论证").assertIsDisplayed()
    compose.onNodeWithText("批判性思考").assertDoesNotExist()
    screenshot("chain")
  }

  @Test fun floorKeepsPriorContextActionAndTopicKeepsItsOwnActions() {
    val context = buildTopicContext(first, first, 2).copy(entryKind = "楼层", ranges = listOf(
      AiReadingRow("主楼正文", "1 楼"), AiReadingRow("选中楼层", "2 楼"), imageReadingRow(emptyList(), null)))
    var selected: QuickAction? = null
    panel(context) { selected = it }
    compose.onNodeWithText("查找前情").performScrollTo().performClick()
    compose.runOnIdle { assertEquals("prior-context", selected?.skillId) }
    compose.onNodeWithText("全屏").performClick()
    compose.onNodeWithText("选中楼层").assertIsDisplayed()
    screenshot("floor")
  }

  @Test fun topicRowAiButtonStartsAnalysisWithoutOpeningTopic() {
    val topic = Topic(42, subject = "讨论", author = "甲")
    var topicOpens = 0
    var aiOpens = 0
    val active = mutableStateOf(false)
    compose.setContent { Ng2nTheme {
      if (!active.value) TopicRow(buildTopicRows(listOf(topic), LocalNg2nColors.current, LocalNg2nTitleColors.current).single(),
        { topicOpens++ }, onAi = { aiOpens++; active.value = true })
      else TopicAiSheetContent(TopicAiState(visible = true, title = "主题 · 讨论", steps = listOf(ReadingStep("第 1 页", true)),
        context = buildTopicContext(first, first).copy(ranges = listOf(AiReadingRow("第 1 页", "4 楼"), AiReadingRow("热门回复", "0 条"), imageReadingRow(emptyList(), null)))),
        {}, {}, {}, {}, {}, {}, {})
    } }
    compose.onNodeWithContentDescription("AI 分析主题").performClick()
    compose.runOnIdle { assertEquals(1, aiOpens); assertEquals(0, topicOpens) }
    compose.onNodeWithText("第 1 页").assertIsDisplayed()
    compose.onNodeWithText("热门回复").assertIsDisplayed()
    compose.onNodeWithText("事实核查").assertIsDisplayed()
    compose.onNodeWithText("查找前情").assertDoesNotExist()
    screenshot("list-topic")
  }

  @Test fun entryPermissionPreviewNavigatesToAccountsAndKeepsConversation() {
    val destinations = mutableListOf<androidx.navigation3.runtime.NavKey>()
    val nav = object : com.chasel.ng2n.ui.nav.Navigator {
      override fun push(key: androidx.navigation3.runtime.NavKey) { destinations += key }
      override fun pop() = Unit
    }
    val credentials = object : com.chasel.ng2n.core.net.CredentialSource {
      override suspend fun current(): com.chasel.ng2n.core.net.Credential? = null
      override suspend fun all() = emptyList<com.chasel.ng2n.core.net.Credential>()
    }
    val transport = com.chasel.ng2n.core.net.Transport { error("测试不得访问网络") }
    val client = com.chasel.ng2n.core.net.NgaClient(object : com.chasel.ng2n.core.net.TransportFactory {
      override fun create() = transport
      override fun renew() = transport
    }, credentials, com.chasel.ng2n.core.net.NetworkSettingsSource.defaults(), com.chasel.ng2n.core.net.UserAgents { "test" },
      readChain = listOf(object : com.chasel.ng2n.core.net.FetchStrategy {
        override val name = "permission-test"
        override suspend fun run(request: com.chasel.ng2n.core.net.NgaRequest, context: com.chasel.ng2n.core.net.FetchContext): com.chasel.ng2n.core.net.StrategyOutcome {
          assertEquals(com.chasel.ng2n.core.net.Operation.READ, request.operation)
          return com.chasel.ng2n.core.net.StrategyOutcome.Failed(com.chasel.ng2n.core.net.NgaError(com.chasel.ng2n.core.net.NgaErrorKind.SERVER, "没有权限"))
        }
      }))
    val keys = com.chasel.ng2n.data.ai.settings.AiKeyStore(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
      override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> get() = error("预览不得读取模型 Key")
      override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences = error("预览不得修改模型 Key")
    }, object : com.chasel.ng2n.data.account.AccountCrypto {
      override fun encrypt(plaintext: ByteArray): String = error("不应加密")
      override fun decrypt(blob: String): ByteArray = error("不应解密")
    })
    val model = object : com.chasel.ng2n.data.ai.TopicAiModel {
      override suspend fun run(key: String, context: TopicContext, history: List<com.chasel.ng2n.data.ai.AiExchange>, question: String,
        onFrame: suspend (com.chasel.ng2n.data.ai.AiStreamUpdate) -> Unit, onStart: suspend () -> Unit): com.chasel.ng2n.data.ai.AiExchange = error("预览不得调用模型")
    }
    val source = AiSource("s1", 42, 2, 2, 1, "甲", "今天", "", emptyList(), "floor")
    val state = TopicAiState(visible = true, position = AiSheetPosition.FULL, entryKind = "回复链", conversationId = "entry-preview",
      context = TopicContext(listOf(source), null, 0, listOf(1)), turns = listOf(AiTurn("概览", "已有回答。[[s1]]", "已完成")))
    compose.setContent { Ng2nTheme {
      val real = com.chasel.ng2n.ui.topic.rememberTopicDeps()
      val vm = remember { TopicAiViewModel(com.chasel.ng2n.ui.topic.TopicDeps(client, real.repository, real.pageLoader, real.history,
        real.bookmarks, real.topicCache, real.settings, credentials, real.attachmentUrls, real.scope), keys, model) }
      EntryAiSheet(state, vm, nav)
    } }
    compose.onNodeWithText("2 楼").performClick()
    compose.onNodeWithText("当前账号无法查看").assertIsDisplayed()
    compose.onNodeWithText("切换账号").performClick()
    compose.runOnIdle { assertEquals(listOf(com.chasel.ng2n.ui.Accounts), destinations) }
    compose.onNodeWithText("当前账号无法查看").assertDoesNotExist()
    compose.onNodeWithText("已有回答。", substring = true).assertIsDisplayed()
  }

  private fun panel(context: TopicContext, onAction: (QuickAction) -> Unit = {}) {
    val state = mutableStateOf(TopicAiState(visible = true, title = "${context.entryKind} · 讨论", entryKind = context.entryKind,
      floorEntry = context.entryKind == "楼层", context = context, steps = listOf(ReadingStep("入口", true))))
    compose.setContent { Ng2nTheme { TopicAiSheetContent(state.value, { state.value = state.value.copy(position = it) }, {}, {}, {}, {}, {}, {}, onQuickAction = onAction) } }
  }
  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.filesDir, "ai-entry-$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
