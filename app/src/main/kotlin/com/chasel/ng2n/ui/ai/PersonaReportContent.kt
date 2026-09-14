package com.chasel.ng2n.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.*

@Composable
fun PersonaReportContent(report: PersonaReport, sources: List<AiSource>, sampleCount: Int, onSource: (AiSource) -> Unit) {
  val colors = LocalNg2nColors.current
  // 补读主楼也会分配来源编号，只有初始样本编号算进已处理数。
  val processed = report.processedSampleCount(sampleCount).coerceAtMost(sampleCount)
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text("已处理 $processed 条 · 未处理 ${(sampleCount - processed).coerceAtLeast(0)} 条", color = colors.meta, fontSize = 12.sp)
    AiMarkdown(report.overview, sources, onSource)
    Text("只整理本人明确表达过的内容", color = colors.meta, fontSize = 12.sp)
    listOf("兴趣与偏好" to report.interests, "议题立场" to report.positions).forEach { (title, cards) ->
      PersonaSectionTitle(title)
      if (cards.isEmpty()) Text("当前样本证据不足，暂不判断。", color = colors.meta, fontSize = 13.5.sp)
      cards.forEach { card -> PersonaTendencyCard(card, sources, onSource) }
    }
    PersonaSectionTitle("判断侧重")
    AiMarkdown(report.judgment, sources, onSource)
    PersonaSectionTitle("稳定性与变化")
    if (report.timeline.isEmpty()) Text("不足以判断稳定性或变化。", color = colors.meta, fontSize = 13.5.sp)
    report.timeline.forEachIndexed { index, event ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(7.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
          Box(Modifier.width(1.dp).fillMaxHeight().background(
            if (index == report.timeline.lastIndex) Color.Transparent else colors.divider))
          Box(Modifier.padding(top = 6.dp).size(7.dp).background(colors.primary, CircleShape))
        }
        Column(Modifier.weight(1f).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(event.period, color = colors.meta, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
          AiMarkdown(event.body + event.sources.joinToString("") { "[[$it]]" }, sources, onSource)
        }
      }
    }
    Row(Modifier.fillMaxWidth().aiShadow(AiShadow.HAIRLINE, RoundedCornerShape(10.dp))
      .background(colors.surface2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AppIcon(icon = Ng2nIcon.INFO, tint = colors.meta, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(PERSONA_BOUNDARY, color = colors.fg2, fontSize = 12.5.sp, lineHeight = 19.sp)
        AiMarkdown(report.boundary, sources, onSource)
      }
    }
  }
}

private const val COUNT_PILL = "persona-count"

private fun AnnotatedString.Builder.appendPersonaText(text: String, ids: Set<String>) {
  parseCitations(text, ids).forEach { part ->
    when (part) {
      is AnswerPart.Text -> append(part.value)
      is AnswerPart.Source -> appendInlineContent(part.id, "[${part.id}]")
    }
  }
}

@Composable
private fun PersonaSectionTitle(text: String) {
  Text(text, color = LocalNg2nColors.current.fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
}

@Composable
private fun PersonaTendencyCard(card: PersonaTendency, sources: List<AiSource>, onSource: (AiSource) -> Unit) {
  val colors = LocalNg2nColors.current
  var drawer by remember(card) { mutableStateOf<String?>(null) }
  val strength = if (card.hits >= 5) 3 else if (card.hits >= 2) 2 else 1
  val signalColor = when (strength) { 3 -> colors.green; 2 -> colors.accent; else -> colors.meta }
  val signalLabel = listOf("偶尔提及", "多次出现", "反复出现")[strength - 1]
  val pillColor = if (strength == 1) colors.fg2 else signalColor
  val pillBackground = when (strength) { 3 -> colors.greenContainer; 2 -> colors.accentContainer; else -> colors.surface2 }
  val shape = RoundedCornerShape(12.dp)
  Column(Modifier.fillMaxWidth().aiShadow(AiShadow.CARD, shape).background(colors.surface, shape).clip(shape)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(card.title, color = colors.fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
      val ids = sources.map { it.id }.toSet()
      val count = card.countPill()
      val inline = aiSourceInline(sources, onSource) + (COUNT_PILL to InlineTextContent(
        Placeholder((card.hits.toString().length * 7 + 32).sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize().background(pillBackground, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
          Text(count, color = pillColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
      })
      Text(buildAnnotatedString {
        appendPersonaText(card.body, ids)
        appendInlineContent(COUNT_PILL, count)
        appendPersonaText(card.bodyTail, ids)
      }, color = colors.fg2, fontSize = 13.5.sp, lineHeight = 22.sp, inlineContent = inline)
    }
    AnimatedVisibility(drawer != null) {
      val counter = drawer == "counter"
      Column(Modifier.fillMaxWidth().background(colors.divider).padding(top = 1.dp).background(colors.surface).padding(8.dp)) {
        Text(if (counter) "相反表述" else "代表性发言", color = colors.meta, fontSize = 11.5.sp,
          fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp))
        val ids = if (counter) card.counter else card.evidence
        ids.mapNotNull { id -> sources.firstOrNull { it.id == id } }.forEach { source ->
          PersonaEvidenceRow(source, counter, onSource)
        }
        if (!counter && card.counter.isEmpty()) Text("已处理样本中未找到相反表述，不代表不存在。",
          color = colors.meta, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
      }
    }
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          val lit = if (strength == 1) 0 else strength
          repeat(3) { index ->
            Box(Modifier.width(4.dp).height(10.dp).background(if (index < lit) signalColor else colors.track, RoundedCornerShape(2.dp)))
          }
        }
        Text(signalLabel, color = colors.fg2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.counter.isNotEmpty()) PersonaCardButton("相反表述 ${card.counter.size}", primary = false,
          active = drawer == "counter") { drawer = if (drawer == "counter") null else "counter" }
        PersonaCardButton(if (drawer == "evidence") "收起依据" else "查看依据", primary = true,
          active = drawer == "evidence") { drawer = if (drawer == "evidence") null else "evidence" }
      }
    }
  }
}

@Composable
private fun PersonaCardButton(label: String, primary: Boolean, active: Boolean, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  val shape = RoundedCornerShape(999.dp)
  Box(Modifier.height(32.dp)
    .then(if (primary) Modifier.background(colors.primary, shape) else Modifier.aiShadow(AiShadow.BTN, shape)
      .background(if (active) colors.surface2 else colors.surface, shape))
    .clip(shape).clickable(onClick = onClick).padding(horizontal = if (primary) 12.dp else 10.dp),
    contentAlignment = Alignment.Center) {
    Text(label, color = if (primary) colors.onPrimary else colors.fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun PersonaEvidenceRow(source: AiSource, counter: Boolean, onSource: (AiSource) -> Unit) {
  val colors = LocalNg2nColors.current
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSource(source) }
    .padding(horizontal = 6.dp, vertical = 8.dp).height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.width(3.dp).fillMaxHeight().background(if (counter) colors.accent else colors.primary, RoundedCornerShape(2.dp)))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("「${source.text.removePrefix("标题：").take(220)}」", color = colors.fg, fontSize = 13.5.sp, lineHeight = 21.sp)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(colors.surface2, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
          Text(personaEvidenceKind(source), color = colors.fg2, fontSize = 11.sp)
        }
        Text(source.postedAt.take(10), color = colors.meta, fontSize = 11.5.sp)
        Text(source.id, color = colors.meta, fontSize = 11.5.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
      }
    }
  }
}
