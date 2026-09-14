package com.chasel.ng2n.ui.ai

import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.ai.AiSheetPosition
import com.chasel.ng2n.data.db.AiConversationEntity
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.settings.AiSettingsKey
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable data class AiHistoryKey(val tid: Long? = null) : NavKey
@Serializable data class AiChatKey(val id: String) : NavKey

internal fun historyGroup(time: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
  when (Instant.ofEpochMilli(time).atZone(zone).toLocalDate()) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> "更早"
  }
internal fun filterAiHistory(items: List<AiConversationEntity>, kind: String, query: String, tid: Long?): List<AiConversationEntity> =
  items.filter { (tid == null || it.tid == tid) && (kind == "全部" || it.kind == kind) &&
    (query.length < 2 || it.title.contains(query, true) || it.source.contains(query, true)) }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AiHistoryScreen(key: AiHistoryKey, nav: Navigator) {
  val sessions = rememberAiSessions()
  val records by sessions.store.conversations.collectAsStateWithLifecycle(emptyList())
  val colors = LocalNg2nColors.current
  val scope = rememberCoroutineScope()
  val snack = remember { SnackbarHostState() }
  var error by remember { mutableStateOf<String?>(null) }
  var searching by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var kind by remember { mutableStateOf("全部") }
  var menu by remember { mutableStateOf<AiConversationEntity?>(null) }
  LaunchedEffect(Unit) { try { sessions.store.initialize() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "历史读取失败，请返回后重试" } }
  BackHandler(searching) { searching = false; query = "" }
  val filtered = filterAiHistory(records, kind, query.trim(), key.tid)
  fun open(item: AiConversationEntity) { nav.push(AiChatKey(item.id)) }
  Scaffold(containerColor = colors.bg, snackbarHost = { SnackbarHost(snack) }, topBar = {
    Row(Modifier.fillMaxWidth().background(colors.topbar).statusBarsPadding().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { if (searching) { searching = false; query = "" } else nav.pop() }) { Text("返回", color = colors.onTopbar) }
      if (searching) {
        androidx.compose.foundation.text.BasicTextField(query, { query = it }, Modifier.weight(1f).padding(8.dp),
          textStyle = androidx.compose.ui.text.TextStyle(color = colors.onTopbar, fontSize = 15.sp), singleLine = true,
          decorationBox = { field -> Box { if (query.isEmpty()) Text("搜索标题、主题或用户", color = colors.onTopbar.copy(alpha = .7f)); field() } })
        TextButton(onClick = { query = "" }) { Text("清除", color = colors.onTopbar) }
      } else {
        Text("AI 对话", Modifier.weight(1f), color = colors.onTopbar, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { searching = true }) { Text("搜索", color = colors.onTopbar) }
      }
    }
  }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (!searching) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("全部", "列表", "主题", "楼层", "回复链", "个人").forEach { value ->
          FilterChip(kind == value, { kind = value }, modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(8.dp), colors = FilterChipDefaults.filterChipColors(
              containerColor = colors.surface, labelColor = colors.fg2,
              selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.primary),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (kind == value) colors.primary.copy(alpha = .3f) else colors.divider),
            label = { Text("$value ${records.count { (key.tid == null || it.tid == key.tid) && (value == "全部" || it.kind == value) }}", fontSize = 13.sp) })
        }
      }
      error?.let { Text(it, Modifier.padding(18.dp), color = colors.danger) }
      if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (query.trim().length >= 2) "没有找到相关对话" else "暂无 AI 对话", color = colors.fg)
        Text(if (query.trim().length >= 2) "「$query」没有匹配的标题、主题或用户" else "从主题或楼层发起分析后自动保存在本机", color = colors.meta, fontSize = 12.sp)
        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除搜索", color = colors.primary) }
      }
      LazyColumn(Modifier.weight(1f)) {
        filtered.groupBy { historyGroup(it.updatedAt) }.forEach { (group, items) ->
          item("group:$group") { Text(group, Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp), color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
          items(items, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { open(item) }, onLongClick = { menu = item }).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Box(Modifier.size(36.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { AppIcon(Ng2nIcon.AUTO_AWESOME, colors.primary, 18.dp) }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(highlight(item.title, query, colors.accentContainer), Modifier.weight(1f), color = colors.fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), color = colors.meta, fontSize = 11.sp)
                }
                Text(highlight(item.source, query, colors.accentContainer), color = colors.fg2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val badge = when {
                  item.status == "生成中" -> "生成中"
                  item.status.contains("额度") -> "已达额度"
                  item.status.contains("删除") || item.status.contains("不可访问") -> "来源已删除"
                  item.status.contains("读取失败") -> "读取失败"
                  item.status.contains("达到限制") -> "达到限制"
                  item.status.contains("中断") || item.status.contains("失败") -> "已中断"
                  else -> null
                }
                badge?.let { Text(it, Modifier.background(colors.accentContainer, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = colors.fg2, fontSize = 11.sp) }
                Text(item.readingSummary, color = colors.meta, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
              TextButton(onClick = { menu = item }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { Text("⋮", color = colors.meta, fontSize = 22.sp) }
            }
            HorizontalDivider(color = colors.divider)
          }
        }
      }
    }
  }
  menu?.let { item ->
    ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = colors.surface) {
      Text(item.title, Modifier.padding(horizontal = 20.dp), color = colors.meta, maxLines = 1)
      TextButton(onClick = { menu = null; open(item) }, Modifier.fillMaxWidth().height(52.dp)) { Text("继续聊天", color = colors.primary) }
      TextButton(onClick = { menu = null; nav.push(TopicKey(item.tid, pid = item.selectedPid)) }, Modifier.fillMaxWidth().height(52.dp)) { Text("打开来源主题", color = colors.primary) }
      TextButton(onClick = {
        menu = null
        scope.launch {
          try {
            sessions.stop(item.id)
            sessions.store.delete(item.id)
            try {
              if (snack.showSnackbar("已删除对话，用量记录保留", "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) sessions.store.undo(item.id)
            } finally { withContext(NonCancellable) { sessions.store.confirmDelete(item.id); if (sessions.store.load(item.id) == null) sessions.forget(item.id) } }
          } catch (e: CancellationException) { throw e }
          catch (_: Exception) { error = "删除或撤销失败，请重试" }
        }
      }, Modifier.fillMaxWidth().height(52.dp)) { Text("删除对话", color = colors.danger) }
      Spacer(Modifier.height(20.dp))
    }
  }
}

private fun highlight(text: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
  val builder = AnnotatedString.Builder(text)
  if (query.trim().length >= 2) {
    var start = text.indexOf(query.trim(), ignoreCase = true)
    while (start >= 0) { builder.addStyle(SpanStyle(background = color), start, start + query.trim().length); start = text.indexOf(query.trim(), start + query.trim().length, true) }
  }
  return builder.toAnnotatedString()
}

@Composable
fun AiChatScreen(key: AiChatKey, nav: Navigator) {
  val sessions = rememberAiSessions()
  val vm = remember(key.id) { sessions.open(key.id) }
  val state by vm.state.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize().background(LocalNg2nColors.current.bg)) {
    if (!state.visible && state.title == "对话已删除") Column(Modifier.statusBarsPadding().padding(24.dp)) {
      Text("对话已删除", color = LocalNg2nColors.current.fg)
      TextButton(onClick = nav::pop) { Text("返回历史") }
    }
    TopicAiSheet(state, vm, onSettings = { nav.push(AiSettingsKey) }, onSource = { nav.push(TopicKey(it.tid, pid = it.pid.takeIf { pid -> pid > 0 }, floor = it.floor, highlightSource = true)) },
      onAccounts = { nav.push(com.chasel.ng2n.ui.Accounts) },
      onHistory = { nav.push(AiHistoryKey()) }, fullScreen = true, onBack = nav::pop)
  }
}
