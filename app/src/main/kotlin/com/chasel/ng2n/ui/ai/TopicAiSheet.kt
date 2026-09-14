package com.chasel.ng2n.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun TopicAiSheet(state: TopicAiState, vm: TopicAiViewModel, onSettings: () -> Unit, onSource: (AiSource) -> Unit, onHistory: () -> Unit = {}, fullScreen: Boolean = false, onBack: () -> Unit = {},
  onAccounts: () -> Unit = {}) {
  var source by remember(state.conversationId) { mutableStateOf<AiSource?>(null) }
  var preview by remember { mutableStateOf<com.chasel.ng2n.data.ai.AiSourcePreview?>(null) }
  var image by remember { mutableStateOf<com.chasel.ng2n.ui.image.ImageViewerKey?>(null) }
  var attempt by remember { mutableIntStateOf(0) }
  LaunchedEffect(source, attempt) { preview = null; source?.takeIf { it.web == null }?.let { preview = vm.preview(it) } }
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  TopicAiSheetContent(state, vm::position, vm::draft, { vm.send() }, vm::stop, vm::retry, onSettings, { preview = null; source = it }, onHistory, fullScreen, onBack, vm::retrySave, vm::quickAction, { vm.send(it) }, source != null, vm::decideBudget)
  source?.takeIf { it.web != null }?.let { selected ->
    AiWebSourcePreviewSheet(selected, onDismiss = { source = null },
      onOpen = { url -> source = null; runCatching { uri.openUri(url) } })
  }
  source?.takeIf { it.web == null }?.let { selected ->
    AiSourcePreviewSheet(selected, preview, state.allowImages, state.readImageUrls,
      onDismiss = { source = null }, onJump = {
        val target = selected.copy(floor = preview?.floor ?: selected.floor, page = preview?.page ?: selected.page)
        source = null
        if (!fullScreen) vm.position(AiSheetPosition.COLLAPSED)
        onSource(target)
      }, onAccounts = { source = null; onAccounts() }, onRetry = { attempt++ }, onImage = { urls, index -> image = com.chasel.ng2n.ui.image.ImageViewerKey(urls, index) })
  }
  image?.let { key -> androidx.compose.ui.window.Dialog(onDismissRequest = { image = null },
    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    com.chasel.ng2n.ui.image.ImageViewerScreen(key, onBack = { image = null }, imageDescription = { index ->
      "图 ${index + 1} · ${if (key.urls[index] in state.readImageUrls) "已带入上下文" else "未读取"}"
    })
  } }
}

@Composable
fun TopicAiSheetContent(state: TopicAiState, onPosition: (AiSheetPosition) -> Unit, onDraft: (String) -> Unit,
  onSend: () -> Unit, onStop: () -> Unit, onRetry: () -> Unit, onSettings: () -> Unit, onSource: (AiSource) -> Unit, onHistory: () -> Unit = {},
  fullScreen: Boolean = false, onBack: () -> Unit = {}, onRetrySave: () -> Unit = {}, onQuickAction: (QuickAction) -> Unit = {}, onSuggestion: (String) -> Unit = {}, previewOpen: Boolean = false, onBudgetDecision: (Boolean) -> Unit = {}) {
  if (!state.visible) return
  val colors = LocalNg2nColors.current
  val collapsed = !fullScreen && state.position == AiSheetPosition.COLLAPSED
  BackHandler(!collapsed) { if (fullScreen) onBack() else onPosition(AiSheetPosition.COLLAPSED) }
  BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val scroll = rememberScrollState()
    var stick by remember { mutableStateOf(true) }
    val followScroll = remember(scroll) {
      object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
          if (consumed.y != 0f) stick = scroll.maxValue - scroll.value < 64
          return Offset.Zero
        }
      }
    }
    if (collapsed) {
      Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
        .aiShadow(AiShadow.OVERLAY, RoundedCornerShape(24.dp)).background(colors.surface, RoundedCornerShape(24.dp))
        .clickable { onPosition(AiSheetPosition.HALF) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("✦ AI · ${state.turns.lastOrNull()?.status.orEmpty()}", color = colors.primary, maxLines = 1)
      }
      return@BoxWithConstraints
    }
    val density = LocalDensity.current
    val heightPx = with(density) { maxHeight.toPx() }
    var drag by remember { mutableStateOf<Float?>(null) }
    val resting = if (fullScreen || state.position == AiSheetPosition.FULL) 0f else 332f / 844
    val animated by animateFloatAsState(resting, label = "aiSheet")
    val top = drag ?: animated
    val sheetShape = RoundedCornerShape(topStart = if (top < 8f / 844) 0.dp else 20.dp, topEnd = if (top < 8f / 844) 0.dp else 20.dp)
    if (!fullScreen) Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = .12f)).clickable { onPosition(AiSheetPosition.COLLAPSED) })
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * (1 - top))
      .aiShadow(AiShadow.OVERLAY, sheetShape)
      .clip(sheetShape).background(colors.bg).then(if (fullScreen) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding()) {
      Column(Modifier.fillMaxWidth().testTag("ai-sheet-handle").pointerInput(heightPx, resting) {
        detectVerticalDragGestures(onDragStart = { drag = resting }, onDragCancel = { drag = null },
          onDragEnd = { if (!fullScreen) onPosition(snapAiSheet(drag ?: resting)); drag = null }) { change, amount ->
          change.consume(); if (!fullScreen) drag = ((drag ?: resting) + amount / heightPx).coerceIn(0f, 684f / 844)
        }
      }) {
        if (!fullScreen) Box(Modifier.fillMaxWidth().height(24.dp).clickable { onPosition(if (state.position == AiSheetPosition.FULL) AiSheetPosition.HALF else AiSheetPosition.FULL) }, contentAlignment = Alignment.Center) {
          Box(Modifier.size(32.dp, 4.dp).background(colors.track, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(if (fullScreen) "✦ AI 对话" else "✦ AI 助手", color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(state.title, color = colors.meta, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          TextButton(onClick = onHistory) { Text("历史", color = colors.fg2) }
          if (!fullScreen) TextButton(onClick = { onPosition(if (state.position == AiSheetPosition.FULL) AiSheetPosition.HALF else AiSheetPosition.FULL) }) { Text(if (state.position == AiSheetPosition.FULL) "半屏" else "全屏", color = colors.fg2) }
          TextButton(onClick = { if (fullScreen) onBack() else onPosition(AiSheetPosition.COLLAPSED) }) { Text(if (fullScreen) "返回" else "收起", color = colors.fg2) }
        }
      }
      LaunchedEffect(state.turns, state.steps, scroll.maxValue, stick, scroll.isScrollInProgress, previewOpen) { if (!previewOpen && stick && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
      Column(Modifier.weight(1f).testTag("ai-chat-scroll").nestedScroll(followScroll).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.steps.isNotEmpty()) Column(Modifier.fillMaxWidth().aiShadow(AiShadow.CARD, RoundedCornerShape(12.dp))
          .background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("阅读范围", color = colors.fg2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
          if (state.context?.ranges.isNullOrEmpty()) state.steps.forEach { Text("${if (it.done) "✓" else "○"}  ${it.label}", color = if (it.done) colors.green else colors.meta, fontSize = 13.sp) }
          state.context?.ranges?.forEach { AiReadingRangeRow(it) }
          state.context?.note?.let { Text(it, color = colors.fg2, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = Modifier.background(colors.surface2, RoundedCornerShape(10.dp)).padding(10.dp)) }
          // 本次准备范围与卡内其余数字同口径；agent 续读会让 sources 增长，不能拿它当作本卡的已读数。
          state.context?.let { Text("已读 ${it.prepared} 条 · 屏蔽 ${it.blocked} 条未计入", color = colors.meta, fontSize = 12.sp) }
        }
        state.turns.forEachIndexed { index, turn ->
          if (index > 0) Row(Modifier.fillMaxWidth().background(colors.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (turn.quickActionId != null) QuickActionBolt()
            Text(turn.question, color = colors.fg)
          }
          var seconds by remember(turn.thinkingStarted) { mutableLongStateOf(0L) }
          LaunchedEffect(turn.thinkingStarted, turn.thoughtSeconds, state.busy) {
            while (turn.thinkingStarted != null && turn.thoughtSeconds == null && state.busy) {
              seconds = (System.nanoTime() - turn.thinkingStarted) / 1_000_000_000; delay(1000)
            }
          }
          if (turn.thoughtSeconds != null) Text("✓ 已思考 ${turn.thoughtSeconds} 秒", color = colors.meta, fontSize = 12.sp)
          else if (turn.thinkingStarted != null && state.busy && index == state.turns.lastIndex) Text("正在思考 · ${seconds} 秒", color = colors.meta, fontSize = 12.sp)
          val readPages = turn.tools.filter { it.name == "read_webpage" && it.status == "ok" }
            .mapNotNull { row -> state.context?.sources?.firstOrNull { it.id == row.sourceId && it.web != null } }
            .distinctBy { it.id }
          ToolCallRows(turn.tools, turn.status == "生成回答" || turn.status == "已完成", readPages) { stick = false; onSource(it) }
          if (com.chasel.ng2n.data.ai.webSearchUnavailable(turn.tools))
            Row(Modifier.fillMaxWidth().testTag("ai-search-notice").background(colors.accentContainer, RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
              Text("!", color = colors.accent, fontWeight = FontWeight.SemiBold)
              Text(com.chasel.ng2n.data.ai.WEB_SEARCH_NOTICE, color = colors.fg, fontSize = 13.5.sp, lineHeight = 21.sp)
            }
          val personaSampleCount = state.context?.sampleCount ?: 0
          val personaSources = state.context?.sources.orEmpty()
          val persona = remember(state.entryKind, turn.text, personaSources, personaSampleCount) {
            if (state.entryKind == "个人") parsePersonaReport(turn.text, personaSources, personaSampleCount) else null
          }
          // 报告结构无效时界面用占位替换整段 JSON：状态卡不能再说「已保留文字」。
          val reportDraft = persona == null && state.entryKind == "个人" &&
            listOf("{", "```persona", "```json").any { turn.text.trimStart().startsWith(it) }
          if (persona != null) PersonaReportContent(persona, state.context?.sources.orEmpty(),
            personaSampleCount) { stick = false; onSource(it) }
          else if (reportDraft) {
            Text(if (state.busy) "正在生成个人分析报告…" else "报告结构尚未完整或来源校验未通过，请继续。已有草稿已保留。", color = colors.meta)
          } else AiMarkdown(turn.text, state.context?.sources.orEmpty()) { stick = false; onSource(it) }
          val cited = state.context?.sources.orEmpty().filter { "[[${it.id}]]" in turn.text }
          // 只有需要提交报告的那一轮才说明待确认处理数；普通追问的回答与报告覆盖无关。
          // 读取失败与零样本连模型都没请求，没有草稿也谈不上继续，这两种卡片下不附这句说明。
          if (turn.reportExpected && turn.incomplete && persona == null && turn.card !in listOf("READ", "EMPTY")) Text(
            personaPendingNote(personaSampleCount, draftShown = turn.text.isNotBlank() && !reportDraft, sourceCount = cited.size),
            color = colors.meta, fontSize = 12.sp)
          if (cited.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text("${cited.size} 个来源 ${if (expanded) "⌃" else "⌄"}", color = colors.fg2) }
            if (expanded) cited.forEach { source -> TextButton(onClick = { stick = false; onSource(source) }) {
              Text("${source.id.removePrefix("s")} · " + when {
                source.web != null -> "站外 · ${aiSourceLabel(source)} · ${webReadLabel(source.web)}"
                source.part == "summary" -> "主题摘要 · ${source.author}"
                else -> "${sourceFloorLabel(source.floor)} · ${source.author}"
              }, color = colors.primary)
            } }
          }
          var inspectUsage by remember(turn.analysisId) { mutableStateOf(false) }
          AiBudgetCard(turn, cited.size, onBudgetDecision, onSettings, { inspectUsage = true }, answerShown = !reportDraft)
          AiUsageDetails(state.usage.requests.filter { it.analysis == turn.analysisId }, analysisLimit = state.usage.analyses.firstOrNull { it.id == turn.analysisId }?.limit, showDetails = inspectUsage)
          // 状态卡已经把同一句话作为标题显示，这里不再重复一遍。
          if (turn.card == null) Text(turn.status, color = if (turn.incomplete) colors.danger else colors.meta, fontSize = 12.sp,
            modifier = if (turn.incomplete) Modifier.fillMaxWidth().background(colors.dangerContainer, RoundedCornerShape(10.dp)).padding(12.dp) else Modifier)
        }
        if (!state.busy && !state.unsaved && state.turns.lastOrNull()?.status == "已完成") {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("继续问", color = colors.fg2, fontSize = 12.sp)
            listOf("哪些发言支持刚才的结论？", "这些观点在什么条件下不成立？").forEach { question ->
              TextButton(onClick = { onSuggestion(question) }) { Text(question, color = colors.primary) }
            }
          }
        }
        state.interruption?.let { Text(it, Modifier.fillMaxWidth().background(colors.accentContainer, RoundedCornerShape(10.dp)).padding(12.dp), color = colors.fg2, fontSize = 13.5.sp, lineHeight = 21.sp) }
        if (state.unsaved) {
          Text("对话未能保存。已暂停后续模型请求，当前草稿仍在内存中，退出进程后可能丢失。", Modifier.fillMaxWidth().background(colors.dangerContainer, RoundedCornerShape(10.dp)).padding(12.dp), color = colors.danger)
          TextButton(onClick = onRetrySave) { Text("重试保存", color = colors.primary) }
        }
        if (state.needsKey) Button(onClick = onSettings, colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) { Text("前往 AI 设置") }
        // 尚未请求模型的读取失败与零样本没有可继续的草稿，同一个按钮改说「重试」。
        if (!state.busy && !state.unsaved && ((state.turns.lastOrNull()?.incomplete == true && state.turns.lastOrNull()?.card !in listOf("budget")) || state.interruption != null)) TextButton(onClick = onRetry) {
          Text(if (state.turns.lastOrNull()?.card in listOf("READ", "EMPTY")) "重试" else "继续", color = colors.primary)
        }
      }
      QuickActionComposer(state, onDraft, onSend, onStop, onQuickAction)
    }
  }
}

@Composable
private fun AiReadingRangeRow(row: AiReadingRow) {
  val colors = LocalNg2nColors.current
  var expanded by remember(row.label) { mutableStateOf(row.expanded) }
  Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(8.dp))) {
    Row(Modifier.fillMaxWidth().clickable(enabled = row.details.isNotEmpty()) { expanded = !expanded }.padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(if (row.skipped) "−" else "✓", color = if (row.skipped) colors.tag else colors.green)
      Text(row.label, Modifier.weight(1f), color = colors.fg, fontSize = 13.5.sp)
      Text(row.amount, color = colors.fg2, fontSize = 12.5.sp)
      row.pill?.let { Text(it, Modifier.background(colors.surface2, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = colors.fg2, fontSize = 11.5.sp) }
      if (row.details.isNotEmpty()) Text(if (expanded) "⌃" else "⌄", color = colors.meta)
    }
    if (expanded) row.details.forEach { (label, value) ->
      Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = colors.fg2, fontSize = 12.5.sp)
        Text(value, color = colors.meta, fontSize = 11.5.sp)
      }
    }
  }
}
