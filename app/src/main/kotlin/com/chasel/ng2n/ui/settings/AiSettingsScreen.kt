package com.chasel.ng2n.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.ai.settings.AiKeyState
import com.chasel.ng2n.data.ai.settings.AnalysisAllowance
import com.chasel.ng2n.data.ai.settings.formatCny
import com.chasel.ng2n.data.ai.settings.parseDailyLimitFen
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.common.DialogShell
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import java.math.BigDecimal
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(onBack: () -> Unit, viewModel: AiSettingsViewModel, onHistory: () -> Unit = {}) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val sessions = com.chasel.ng2n.ui.ai.rememberAiSessions()
  val records by sessions.store.conversations.collectAsStateWithLifecycle(emptyList())
  val requestCount by sessions.store.requestCount.collectAsStateWithLifecycle(0)
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var clearing by remember { mutableStateOf(false) }
  var clearError by remember { mutableStateOf<String?>(null) }
  androidx.compose.runtime.LaunchedEffect(Unit) { try { sessions.store.initialize() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { clearError = "对话历史读取失败" } }
  if (clearing) androidx.compose.material3.AlertDialog(onDismissRequest = { clearing = false },
    title = { Text("清空全部对话？") }, text = { Text(clearError ?: "将删除全部对话、检查点和模型工作上下文。用量记录保留。") },
    confirmButton = { TextButton(onClick = { scope.launch {
      try { sessions.stopAll(); sessions.store.clear(); sessions.discardAll(); clearing = false }
      catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { clearError = "清空失败，请重试" }
    } }) { Text("清空全部对话") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
  AiSettingsContent(
    state = state,
    onBack = onBack,
    saveKey = viewModel::saveKey,
    setAllowance = viewModel::setAllowance,
    setDailyEnabled = viewModel::setDailyEnabled,
    setDailyLimit = viewModel::setDailyLimit,
    historyCount = records.size, requestCount = requestCount, onHistory = onHistory, onClear = { clearing = true },
  )
}

@Composable
internal fun AiSettingsContent(
  state: AiSettingsUiState,
  onBack: () -> Unit,
  saveKey: (String, () -> Unit) -> Unit,
  setAllowance: (AnalysisAllowance, () -> Unit) -> Unit,
  setDailyEnabled: (Boolean) -> Unit,
  setDailyLimit: (Long, () -> Unit) -> Unit,
  requestCount: Int = 0, historyCount: Int = 0, onHistory: () -> Unit = {}, onClear: () -> Unit = {},
) {
  val colors = LocalNg2nColors.current
  var dialog by remember { mutableStateOf<String?>(null) }
  var input by remember { mutableStateOf("") }
  var revealed by remember { mutableStateOf(false) }
  val settings = state.settings
  fun close() {
    if (!state.saving) {
      dialog = null
      input = ""
      revealed = false
    }
  }
  fun openDaily() {
    input = settings.dailyLimitFen?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
    dialog = "daily"
  }

  SettingsShell(title = "AI 助手", onBack = onBack, overlays = {
    SettingsOptionDialog(
      open = dialog == "allowance",
      title = "单次分析额度",
      options = AnalysisAllowance.entries.map { SettingsOption(it, it.label) },
      value = settings.allowance,
      hint = state.error ?: "达到额度时保留已有结果，由你决定是否追加额度继续。暂定额度：短问答 ¥0.2、默认 ¥0.5、长楼 ¥1、更高 ¥2；仍待真实样本校准。",
      onCancel = ::close,
      onConfirm = { if (!state.saving) setAllowance(it) { dialog = null } },
    )
    DialogShell(open = dialog == "key" || dialog == "daily", onDismiss = ::close) {
      val isKey = dialog == "key"
      val fen = if (isKey) null else parseDailyLimitFen(input)
      val valid = if (isKey) input.trim().isNotEmpty() &&
        input.trim().none { it.isWhitespace() || it.isISOControl() } else fen != null
      Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (isKey) "DeepSeek API Key" else "每日上限（¥）", color = colors.fg, fontSize = 18.sp)
        TextField(
          value = input,
          onValueChange = { input = it },
          modifier = Modifier.fillMaxWidth(),
          enabled = !state.saving,
          singleLine = true,
          label = { Text(if (isKey) "输入 API Key" else "金额，最多两位小数") },
          visualTransformation = if (isKey && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
          keyboardOptions = KeyboardOptions(keyboardType = if (isKey) KeyboardType.Password else KeyboardType.Decimal),
          trailingIcon = if (isKey) ({
            TextButton(onClick = { revealed = !revealed }, colors = ButtonDefaults.textButtonColors(contentColor = colors.fg2)) { Text(if (revealed) "隐藏" else "显示") }
          }) else null,
          colors = TextFieldDefaults.colors(
            focusedTextColor = colors.fg, unfocusedTextColor = colors.fg,
            focusedContainerColor = colors.menu, unfocusedContainerColor = colors.menu,
            cursorColor = colors.primary, focusedIndicatorColor = colors.primary,
            unfocusedIndicatorColor = colors.divider,
            focusedLabelColor = colors.primary, unfocusedLabelColor = colors.fg2,
          ),
        )
        Text(
          if (isKey) "在 platform.deepseek.com 创建。Key 只用于直连 DeepSeek，不随论坛请求发送，日志不记录。留空或取消会保留原 Key。"
          else "只统计本机发起的请求。达到后需修改额度才能继续。请输入大于 0 的人民币金额。",
          color = colors.fg2, fontSize = 12.sp,
        )
        state.error?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = ::close, enabled = !state.saving, colors = ButtonDefaults.textButtonColors(contentColor = colors.fg2)) { Text("取消") }
          Button(enabled = valid && !state.saving, colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary, contentColor = colors.onPrimary,
            disabledContainerColor = colors.track, disabledContentColor = colors.meta,
          ), onClick = {
            val saved = { dialog = null; input = ""; revealed = false }
            if (isKey) saveKey(input, saved) else setDailyLimit(requireNotNull(fen), saved)
          }) { Text(if (state.saving) "保存中…" else "保存") }
        }
      }
    }
  }) {
    item("model") { SettingsSection("模型服务") }
    item("provider") { AiInfoRow("服务商", "DeepSeek 官方 · 其他服务商后续提供") }
    item("key") {
      AiInfoRow("API Key", when (state.key) {
        AiKeyState.Missing -> "未配置 · 点击输入"
        AiKeyState.Saved -> "•••••••• · 加密保存在本机"
        AiKeyState.Unreadable -> "无法读取已保存的 Key，请重新输入"
      }) { if (!state.saving) { input = ""; revealed = false; dialog = "key" } }
    }
    item("default-model") { AiInfoRow("默认模型", "deepseek-flash · 图片、工具调用与思考") }
    item("cost") { SettingsSection("开销控制") }
    item("allowance") {
      AiInfoRow("单次分析额度 · ${settings.allowance.label}", "概览和每次追问各算一次，调用、思考、图片、整理与重试共用") {
        if (state.loaded && !state.saving) dialog = "allowance"
      }
    }
    item("daily") {
      SettingsSwitchRow("每日额度", settings.dailyEnabled,
        "只统计本机发起的请求，不含同一 Key 在其他设备的消费") { enabled ->
        if (state.loaded && !state.saving) {
          if (enabled && settings.dailyLimitFen == null) openDaily() else setDailyEnabled(enabled)
        }
      }
    }
    if (settings.dailyEnabled) item("limit") {
      AiInfoRow("每日上限 · ${formatCny(requireNotNull(settings.dailyLimitFen))}", "达到后需修改额度才能继续") {
        if (!state.saving) openDaily()
      }
    }
    item("usage") {
      if (!settings.dailyEnabled) Text("未设每日上限", Modifier.padding(horizontal = Spacing.page), color = colors.meta, fontSize = 12.sp)
      com.chasel.ng2n.ui.ai.AiUsageDetails(state.usage.requests.filter {
        it.day == com.chasel.ng2n.core.ai.aiDay(System.currentTimeMillis()) || it.cost == null
      }, "今日用量", if (settings.dailyEnabled) settings.dailyLimitFen?.times(10_000) else null)
    }
    item("history-section") { SettingsSection("对话历史") }
    item("history") { AiInfoRow("AI 对话历史", "$historyCount 条对话 · 本机保存 · 全账号共享", onHistory) }
    item("clear-history") { AiInfoRow("清空全部对话", "对话与检查点一并删除，用量记录保留", onClear) }
    item("hint") {
      Text("单次额度为暂定值，仍待真实样本校准。费用为估算，以服务商账单为准。",
        Modifier.padding(horizontal = Spacing.page), color = colors.meta, fontSize = 12.sp)
    }
    if (state.error != null) item("error") {
      Text(state.error, Modifier.padding(Spacing.page), color = colors.danger)
    }
  }
}

@Composable
private fun AiInfoRow(label: String, sub: String, onClick: (() -> Unit)? = null) {
  val colors = LocalNg2nColors.current
  Row(
    Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
      .drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      }.padding(horizontal = Spacing.page, vertical = Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.row),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(label, color = colors.fg, fontSize = 15.sp)
      Text(sub, color = colors.fg2, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
    if (onClick != null) AppIcon(Ng2nIcon.CHEVRON_RIGHT, tint = colors.meta, size = 20.dp)
  }
}
