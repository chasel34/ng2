package com.chasel.ng2n.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.AiShadow
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.aiShadow

fun aiSourceLabel(source: AiSource): String = when {
  source.web != null -> webDomain(source.web.url)
  source.part == "summary" -> "主题"
  else -> sourceFloorLabel(source.floor)
}

@Composable
fun WebSourceIcon(size: Dp) {
  AppIcon(Ng2nIcon.PUBLIC, LocalNg2nColors.current.meta, size)
}

@Composable
fun aiSourceInline(sources: List<AiSource>, onSource: (AiSource) -> Unit): Map<String, InlineTextContent> {
  val colors = LocalNg2nColors.current
  return sources.associate { source ->
    val label = aiSourceLabel(source)
    val width = when {
      source.web != null -> label.length * 6f + 32f
      source.part == "summary" -> 42f
      else -> source.floor.toString().length * 7f + 24f
    }
    source.id to InlineTextContent(Placeholder(width.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
      Row(Modifier.fillMaxSize().background(colors.surface2, RoundedCornerShape(5.dp)).clickable { onSource(source) }
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        if (source.web != null) { WebSourceIcon(10.dp); Spacer(Modifier.width(3.dp)) }
        Text(label, color = colors.fg2, fontSize = 11.sp, maxLines = 1)
      }
    }
  }
}

@Composable
fun AiMarkdown(text: String, sources: List<AiSource>, onSource: (AiSource) -> Unit) {
  val blocks = splitAnswerBlocks(text)
  if (blocks.none { it is AnswerBlock.Claim }) { AiMarkdownLines(text, sources, onSource); return }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    blocks.forEach { block ->
      when (block) {
        is AnswerBlock.Markdown -> AiMarkdownLines(block.text, sources, onSource)
        is AnswerBlock.Claim -> AiClaimCard(block.value, sources, onSource)
      }
    }
  }
}

@Composable
private fun AiClaimCard(claim: AiClaim, sources: List<AiSource>, onSource: (AiSource) -> Unit) {
  val colors = LocalNg2nColors.current
  val tone = when (claim.status) {
    "支持" -> colors.green to colors.greenContainer
    "部分支持" -> colors.accent to colors.accentContainer
    else -> colors.fg2 to colors.surface2
  }
  Column(Modifier.fillMaxWidth().aiShadow(AiShadow.CARD, RoundedCornerShape(12.dp))
    .background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(claim.status, Modifier.background(tone.second, RoundedCornerShape(10.dp))
        .padding(horizontal = 7.dp, vertical = 2.dp), color = tone.first, fontSize = 12.sp, fontWeight = FontWeight.Medium)
      Text("说法 ${claim.index}", color = colors.meta, fontSize = 12.sp)
    }
    AiMarkdownLines(claim.claim, sources, onSource, emphasis = true)
    if (claim.note.isNotEmpty()) AiMarkdownLines(claim.note, sources, onSource, secondary = true)
  }
}

@Composable
private fun AiMarkdownLines(text: String, sources: List<AiSource>, onSource: (AiSource) -> Unit,
  emphasis: Boolean = false, secondary: Boolean = false) {
  val colors = LocalNg2nColors.current
  val uri = LocalUriHandler.current
  val byId = sources.associateBy { it.id }
  val safe = parseCitations(text, byId.keys).joinToString("") {
    when (it) { is AnswerPart.Text -> it.value; is AnswerPart.Source -> "[[${it.id}]]" }
  }
  val inline = aiSourceInline(byId.values.toList(), onSource)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    var fenced = false
    safe.lines().forEach { line ->
      if (!fenced && line.trim().startsWith("|") && line.trim().endsWith("|")) {
        val cells = line.trim().removePrefix("|").removeSuffix("|").split('|')
        if (cells.all { it.trim().matches(Regex(":?-{3,}:?")) }) return@forEach
        Row(Modifier.fillMaxWidth()) {
          cells.forEach { cell ->
            Box(Modifier.weight(1f).border(1.dp, colors.divider).padding(6.dp)) {
              AiMarkdown(cell.trim(), sources, onSource)
            }
          }
        }
        return@forEach
      }
      if (line.trimStart().startsWith("```")) { fenced = !fenced; return@forEach }
      val codeBlock = fenced
      val heading = if (codeBlock) null else Regex("^(#{1,6}) +").find(line)
      val quote = !codeBlock && line.startsWith("> ")
      val content = when {
        codeBlock -> line
        heading != null -> line.removeRange(heading.range)
        quote -> line.drop(2)
        line.startsWith("- ") || line.startsWith("* ") -> "• " + line.drop(2)
        else -> line
      }
      val annotated = buildAnnotatedString {
        fun appendMarkup(value: String, depth: Int = 0, literal: Boolean = codeBlock) {
          if (depth > 12) { append(value); return }
          var i = 0
          while (i < value.length) {
            val citation = if (value.startsWith("[[", i)) value.indexOf("]]", i + 2) else -1
            if (citation >= 0) {
              val id = value.substring(i + 2, citation)
              if (id in byId) appendInlineContent(id, "[${aiSourceLabel(byId.getValue(id))}]")
              i = citation + 2; continue
            }
            if (!literal) {
              val delimiter = when { value.startsWith("**", i) -> "**"; value[i] == '*' -> "*"; value[i] == '`' -> "`"; else -> null }
              val end = delimiter?.let { value.indexOf(it, i + it.length) } ?: -1
              if (delimiter != null && end > i + delimiter.length) {
                val style = when (delimiter) { "**" -> SpanStyle(fontWeight = FontWeight.SemiBold); "*" -> SpanStyle(fontStyle = FontStyle.Italic); else -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surface2) }
                withStyle(style) { appendMarkup(value.substring(i + delimiter.length, end), depth + 1, literal = delimiter == "`") }
                i = end + delimiter.length; continue
              }
              val link = if (value[i] == '[') Regex("\\[([^]\\n]+)]\\((https?://[^ )]+)\\)").find(value, i)?.takeIf { it.range.first == i } else null
              if (link != null) {
                withLink(LinkAnnotation.Clickable(link.groupValues[2], TextLinkStyles(SpanStyle(color = colors.link))) {
                  runCatching { uri.openUri(link.groupValues[2]) }
                }) { append(link.groupValues[1]) }
                i = link.range.last + 1; continue
              }
            }
            append(value[i++])
          }
        }
        appendMarkup(content)
      }
      Text(annotated, inlineContent = inline, color = if (quote || secondary) colors.fg2 else colors.fg,
        fontSize = if (heading != null) (22 - heading.groupValues[1].length).sp else if (secondary) 13.5.sp else 15.sp,
        lineHeight = if (secondary) 22.sp else 25.sp,
        fontWeight = if (heading != null || emphasis) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (codeBlock || line.startsWith("|")) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.fillMaxWidth().then(if (quote || codeBlock) Modifier.background(colors.surface2, RoundedCornerShape(6.dp)).padding(8.dp) else Modifier))
    }
  }
}
