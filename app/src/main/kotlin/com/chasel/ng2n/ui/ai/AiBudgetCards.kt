package com.chasel.ng2n.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.*

@Composable
fun AiBudgetCard(turn: AiTurn, sourceCount: Int, onDecide: (Boolean) -> Unit, onSettings: () -> Unit, onUsage: () -> Unit = {}, answerShown: Boolean = true) {
  val colors = LocalNg2nColors.current
  var hidden by remember(turn.analysisId) { mutableStateOf(false) }
  var choice by remember(turn.analysisId) { mutableStateOf<Boolean?>(null) }
  turn.decision?.let {
    Row(Modifier.background(colors.greenContainer, RoundedCornerShape(20.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      AppIcon(Ng2nIcon.CHECK, colors.green, 14.dp)
      Text(it, color = colors.green, fontSize = 13.sp)
    }
  }
  if (turn.card == null) return
  if (turn.card == "budget" && hidden) {
    TextButton(onClick = { hidden = false }) { Text("已达到本次额度 · 待确认", color = colors.accent) }
    return
  }
  Column(Modifier.fillMaxWidth().aiShadow(AiShadow.CARD, RoundedCornerShape(12.dp))
    .background(if (turn.card in listOf("budget", "EMPTY")) colors.surface else if (turn.card in listOf("daily", "UNKNOWN", "LIMIT")) colors.accentContainer else colors.dangerContainer, RoundedCornerShape(12.dp))
    .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(if (turn.card == "budget") "已达到本次额度，要继续吗？" else turn.status, Modifier.weight(1f), color = colors.fg, fontSize = 15.sp)
      if (turn.card == "budget") TextButton(onClick = { hidden = true }) { Text("关闭", color = colors.meta) }
    }
    // 「N 个来源」与回答页脚同为本轮引用数；本轮没有文字、或文字因格式无效没有展示时都不能宣称已保留文字。
    val kept = when {
      turn.text.isBlank() -> "本轮没有生成文字，已读取的资料已保留。"
      !answerShown -> "本轮输出的报告结构不完整或未通过来源校验，界面没有展示这段内容；已读取的资料与来源已保留。"
      else -> "已保留文字和 $sourceCount 个来源。"
    }
    Text(when (turn.card) {
      "budget" -> "已有结果与 $sourceCount 个来源已保留。追加额度仍计入本对话和今日用量。"
      "daily" -> "已有结果已保留。请修改每日额度，普通继续不能绕过每日额度。"
      "UNKNOWN" -> "请求已发出但没有收到完整响应，服务端可能已经处理并计费。没有自动重试，用量待核实。"
      "AUTH", "BALANCE" -> "请在设置中检查 API Key 或服务商余额，修正后回到本对话继续。"
      "READ" -> "内容未读取完成，已保留输入与可用资料，尚未请求模型。"
      "EMPTY" -> "本次范围内没有可分析的发言，没有请求模型，也没有产生费用。"
      "PARAMETERS" -> "请求格式或模型能力不兼容，没有原样重试。已有输入与资料已保留。"
      "OFFLINE" -> "请求没有发出，本次没有产生费用。已有输入与资料已保留。"
      "LIMIT" -> kept + "继续会复用已读资料和检查点，可能产生新的费用。"
      else -> kept + "用量可能不完整，继续可能产生新的费用。"
    }, color = colors.fg2, fontSize = 13.sp)
    if (turn.card == "budget") {
      Column(Modifier.selectableGroup()) {
        listOf(true to "追加 ${aiMoney(turn.addition)} 额度继续", false to "到此为止").forEach { (more, label) ->
          Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = choice == more, role = Role.RadioButton, onClick = { choice = more }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == more, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.tag))
            Column {
              Text(label, color = colors.fg, fontSize = 14.sp)
              Text(if (more) "计入本对话和今日用量" else "保留已有结果，不再发起请求", color = colors.meta, fontSize = 12.sp)
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { hidden = true; onUsage() }) { Text("查看用量", color = colors.fg2) }
        Button(onClick = { choice?.let(onDecide) }, enabled = choice != null,
          colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) { Text("确定") }
      }
    }
    if (turn.card in listOf("daily", "AUTH", "BALANCE")) TextButton(onClick = onSettings) { Text(if (turn.card == "daily") "修改额度" else "去设置") }
  }
}

@Composable
fun AiUsageDetails(requests: List<AiBudgetRequest>, title: String = "估算费用", dailyLimit: Long? = null, analysisLimit: Long? = null, showDetails: Boolean = false) {
  val colors = LocalNg2nColors.current
  var expanded by remember { mutableStateOf(false) }
  LaunchedEffect(showDetails) { if (showDetails) expanded = true }
  val amount = requests.sumOf { it.charged }
  val sent = requests.filter { it.status !in listOf("reserved", "not_sent") }
  val pending = sent.count { it.cost == null }
  Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("$title · ≈ ${aiMoney(amount)}", Modifier.weight(1f), color = colors.fg2, fontSize = 13.sp)
      Text(if (expanded) "收起" else "明细", color = colors.primary, fontSize = 12.sp)
      AppIcon(if (expanded) Ng2nIcon.EXPAND_LESS else Ng2nIcon.EXPAND_MORE, colors.primary, 14.dp, Modifier.padding(start = 2.dp))
    }
    analysisLimit?.let { Text("本次剩余额度 ${aiMoney((it - amount).coerceAtLeast(0))}", color = colors.meta, fontSize = 12.sp) }
    dailyLimit?.let {
      val ratio = (amount.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
      LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.track)
      Text("每日 ${aiMoney(it)} · ${(ratio * 100).toInt()}%（含未结预留）", color = colors.meta, fontSize = 12.sp)
    }
    if (expanded) {
      listOf("模型请求" to "${sent.size} 次", "输入" to "${requests.sumOf { it.input }} tokens",
        "缓存命中输入" to "${requests.sumOf { it.cached }} tokens", "输出（含思考）" to "${requests.sumOf { it.output }} tokens",
        "网页" to "${requests.sumOf { it.web }} 次", "图片输入" to "${sent.sumOf { it.images }} 张次", "自动重试" to "${sent.count { it.retry }} 次").forEach { (label, value) ->
        Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), color = colors.meta, fontSize = 12.sp); Text(value, color = colors.fg2, fontSize = 12.sp) }
      }
      Text("按请求保存的价格表估算；未知缓存按未命中，时段不确定按峰时价。价格核实于 2026-09-14。", color = colors.meta, fontSize = 12.sp)
    }
    if (requests.any { it.status == "reserved" }) Text("已预留额度，尚未发送模型请求", color = colors.meta, fontSize = 12.sp)
    if (pending > 0) Text("$pending 次请求用量待核实，保守预留未清零，跨日仍占用额度；token 明细仅含已收到用量。", color = colors.accent, fontSize = 12.sp)
  }
}
