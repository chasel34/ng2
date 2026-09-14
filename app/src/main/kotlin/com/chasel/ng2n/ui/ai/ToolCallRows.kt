package com.chasel.ng2n.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.CircularProgressIndicator
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.FlowRow
import com.chasel.ng2n.core.ai.AiSource
import com.chasel.ng2n.core.ai.webDomain
import com.chasel.ng2n.core.ai.webReadLabel
import com.chasel.ng2n.data.ai.ToolCallRow
import com.chasel.ng2n.ui.theme.AiShadow
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.aiShadow

@Composable
fun ToolCallRows(rows: List<ToolCallRow>, answering: Boolean, pages: List<AiSource> = emptyList(),
  onSource: (AiSource) -> Unit = {}) {
  if (rows.isEmpty()) return
  val colors = LocalNg2nColors.current
  var choice by remember { mutableStateOf<Boolean?>(null) }
  val open = choice ?: !answering
  // 开始回答时的自动收起不做动画：流式期间高度渐变会让贴底跟随的内容来回抖动。
  var animate by remember { mutableStateOf(false) }
  val settled = rows.none { it.status == "running" }
  val failures = rows.count { it.status !in listOf("running", "ok", "cancelled") }
  Column(Modifier.fillMaxWidth().testTag("ai-tool-calls")) {
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(150), label = "tool-chevron")
    Row(Modifier.clickable { animate = true; choice = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      AppIcon(Ng2nIcon.EXPAND_MORE, colors.meta, 16.dp, Modifier.padding(end = 6.dp).graphicsLayer { rotationZ = rotation })
      Text("${rows.size} 次工具调用${if (settled && failures > 0) "，$failures 次失败" else ""}", color = colors.fg2, fontSize = 13.sp)
    }
    AnimatedVisibility(open, enter = if (animate) expandVertically(tween(300)) else EnterTransition.None,
      exit = if (animate) shrinkVertically(tween(300)) else ExitTransition.None) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row -> key(row.id) {
          var expanded by remember { mutableStateOf(false) }
          val failed = row.status !in listOf("running", "ok", "cancelled")
          val foreground = if (failed) colors.danger else colors.fg2
          var shown by remember { mutableStateOf(false) }
          LaunchedEffect(Unit) { shown = true }
          AnimatedVisibility(shown, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }) {
          Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { expanded = !expanded; choice = true },
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (row.status == "running" && !expanded) CircularProgressIndicator(Modifier.size(12.dp), color = foreground, strokeWidth = 1.5.dp)
                else AppIcon(if (expanded) Ng2nIcon.EXPAND_MORE else if (failed) Ng2nIcon.ERROR_OUTLINE else Ng2nIcon.CHECK, foreground, 16.dp)
              }
              val label = when (row.name) {
                "__read_file__" -> "读取技能文档"
                "__list_directory__" -> "查看技能目录"
                "read_topic_page" -> "读取主题页"
                "read_user_history" -> "读取用户历史"
                "read_floor" -> "定位楼层"
                "read_reply_chain" -> "读取回复链"
                "list_images" -> "列出图片"
                "read_image" -> "读取图片"
                "search_web" -> "搜索网页"
                "read_webpage" -> "读取网页"
                else -> row.name.trim('_').replace('_', ' ')
              }
              if (row.status == "running") {
                val animation = rememberInfiniteTransition(label = "tool-shimmer")
                val x by animation.animateFloat(-200f, 400f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "tool-shimmer-position")
                Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium,
                  brush = Brush.linearGradient(listOf(colors.meta, colors.fg, colors.meta), Offset(x, 0f), Offset(x + 180f, 0f))))
              } else Text(label, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              // 参数只在收起时用一行摘要显示，展开后给出完整参数与明细，不重复同一段文字。
              if (!expanded) Text(row.arguments, Modifier.weight(1f).background(if (failed) colors.dangerContainer else colors.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp), color = foreground, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
              else Spacer(Modifier.weight(1f))
            }
            AnimatedVisibility(expanded) {
              Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.track))
                Text(listOf(row.arguments, row.detail).filter { it.isNotBlank() }.joinToString("\n"),
                  Modifier.padding(start = 14.dp), color = foreground, fontSize = 12.sp)
              }
            }
          }
          }
        } }
        val settledPages = if (settled) pages else emptyList()
        if (settledPages.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          settledPages.forEach { page ->
            var shown by remember(page.id) { mutableStateOf(false) }
            LaunchedEffect(page.id) { shown = true }
            AnimatedVisibility(shown, enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = .9f)) {
              Row(Modifier.height(30.dp).aiShadow(AiShadow.BTN, RoundedCornerShape(6.dp))
                .background(colors.surface, RoundedCornerShape(6.dp)).clickable { onSource(page) }
                .padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WebSourceIcon(9.dp)
                Text(webDomain(checkNotNull(page.web).url), color = colors.fg, fontSize = 11.5.sp)
                Text(webReadLabel(page.web), color = colors.meta, fontSize = 11.5.sp)
              }
            }
          }
        }
      }
    }
  }
}
