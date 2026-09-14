package com.chasel.ng2n.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.ai.AiSource
import com.chasel.ng2n.core.ai.sourceJumpLabel
import com.chasel.ng2n.core.ai.webDomain
import com.chasel.ng2n.core.ai.webReadLabel
import com.chasel.ng2n.core.ai.webReadState
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.data.ai.AiSourcePreview
import com.chasel.ng2n.ui.bbcode.*
import com.chasel.ng2n.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSourcePreviewSheet(source: AiSource, preview: AiSourcePreview?, allowImages: Boolean,
  readImages: Set<String>, onDismiss: () -> Unit, onJump: () -> Unit, onAccounts: () -> Unit,
  onRetry: () -> Unit, onImage: (List<String>, Int) -> Unit) {
  val colors = LocalNg2nColors.current
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = colors.bg, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
    Column(Modifier.fillMaxWidth().testTag("ai-source-preview").padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("论坛原文", color = colors.fg, fontSize = 14.sp)
        Text(source.id.removePrefix("s"), Modifier.background(colors.surface2, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp), color = colors.fg2)
        Spacer(Modifier.weight(1f))
        Text(if (preview?.status == "ok") "刚刚重新读取" else if (preview?.status == "range") "当前范围已重读" else if (preview == null) "正在重新读取" else "读取结果", color = colors.meta, fontSize = 12.sp)
      }
      Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).aiShadow(AiShadow.CARD, RoundedCornerShape(12.dp))
        .background(colors.surface, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (preview?.status) {
          null -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.primary)
          "ok", "range", "filtered" -> {
            if (preview.status == "range") Text("旧引用缺少精确定位信息。以下为父楼及其当前贴条范围，无法确认原引用对应哪条内容或是否仍存在。", color = colors.fg2)
            PreviewBody(preview, allowImages, readImages, onImage)
            if (preview.notes.isNotEmpty()) {
              Text("当前贴条 · ${preview.notes.size} 条", color = colors.meta, fontSize = 12.sp)
              preview.notes.forEach { note ->
                HorizontalDivider(color = colors.divider)
                PreviewBody(note, allowImages, readImages, onImage)
              }
            } else if (preview.status == "range") Text("当前没有贴条。", color = colors.meta)
            if (preview.title.isNotEmpty()) Text("${preview.title} · 第 ${preview.page} 页", color = colors.meta, fontSize = 12.sp)
          }
          "unavailable" -> {
            Text("已删除或不可访问", color = colors.danger)
            Text("旧引用保持原样。需要引用原句时会重新读取，无法读取的内容不会作为原文展示。", color = colors.fg2)
          }
          "permission_denied" -> { Text("当前账号无法查看", color = colors.danger); Text("切换账号后可重新读取。旧引用保持原样。", color = colors.fg2) }
          else -> { Text("请求失败，未读取该资料", color = colors.danger); TextButton(onClick = onRetry) { Text("重新读取", color = colors.primary) } }
        }
      }
      val jumpable = preview?.status in listOf("ok", "range", "permission_denied")
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(44.dp)) { Text("返回对话", color = colors.fg2) }
          Button(onClick = if (preview?.status == "permission_denied") onAccounts else onJump,
            enabled = jumpable, modifier = Modifier.weight(1.3f).height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
              disabledContainerColor = colors.surface2, disabledContentColor = colors.meta)) {
            Text(if (preview?.status == "permission_denied") "切换账号"
              else sourceJumpLabel(preview?.takeIf { it.status in listOf("ok", "range") }?.floor ?: source.floor))
          }
        }
        if (preview != null && !jumpable) Text(when (preview.status) {
          "unavailable" -> "该来源已删除或不可访问，无法跳转。"
          "filtered" -> "该来源已被屏蔽，无法跳转。"
          else -> "重新读取成功后才能跳转。"
        }, color = colors.meta, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun PreviewBody(preview: AiSourcePreview, allowImages: Boolean, readImages: Set<String>, onImage: (List<String>, Int) -> Unit) {
  val colors = LocalNg2nColors.current
  if (preview.bodyFiltered || preview.status == "filtered") {
    Text(if (preview.isNote) "该贴条已被屏蔽，未展示正文。" else "该来源已被屏蔽，未展示正文。", color = colors.fg2)
    return
  }
  Row {
    Text("${preview.floor} 楼${if (preview.isNote) " · 贴条" else ""} · ${preview.author}", Modifier.weight(1f), color = colors.fg)
    Text("${preview.text.length} 字", color = colors.meta, fontSize = 12.sp)
  }
  // 图片以明确操作打开，查看原文不会把未读图片批量下载或送入模型。
  val ast = remember(preview.content) { previewTextNodes(parseBBCode(preview.content)) }
  val model = remember(ast, colors) { RenderModelBuilder.build(ast, BBCodeRenderOptions(preview.attachBase, colors = colors)) }
  BBCodeContent(model)
  if (allowImages && preview.images.isNotEmpty()) {
    val included = preview.images.mapIndexedNotNull { index, url -> (index + 1).takeIf { url in readImages } }
    val unread = preview.images.size - included.size
    Text((if (included.isEmpty()) "${unread} 张图片未读取" else "图 ${included.joinToString("、")} 已带入上下文" + if (unread > 0) "，另 $unread 张未读取" else "") +
      "\n正文内联图与图片附件一并计入，同一地址只算一张。",
      Modifier.background(colors.surface2, RoundedCornerShape(8.dp)).padding(8.dp), color = colors.fg2, fontSize = 12.sp)
    preview.images.forEachIndexed { index, url ->
      TextButton(onClick = { onImage(preview.images, index) }) { Text("查看图 ${index + 1} · ${if (url in readImages) "已带入上下文" else "未读取"}", color = colors.primary) }
    }
  }
}

private fun previewTextNodes(nodes: List<com.chasel.ng2n.core.bbcode.BBCodeNode>): List<com.chasel.ng2n.core.bbcode.BBCodeNode> = nodes.map { node ->
  when (node) {
    is com.chasel.ng2n.core.bbcode.ImageNode, is com.chasel.ng2n.core.bbcode.AlbumNode -> com.chasel.ng2n.core.bbcode.TextNode("[图片]")
    is com.chasel.ng2n.core.bbcode.TableNode -> node.copy(rows = node.rows.map { row -> row.copy(cells = row.cells.map { cell -> cell.copy(children = previewTextNodes(cell.children)) }) })
    is com.chasel.ng2n.core.bbcode.ListNode -> node.copy(items = node.items.map { previewTextNodes(it) })
    is com.chasel.ng2n.core.bbcode.ChildBearing -> node.withChildren(previewTextNodes(node.children))
    else -> node
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWebSourcePreviewSheet(source: AiSource, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
  val colors = LocalNg2nColors.current
  val web = checkNotNull(source.web)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = colors.bg, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
    Column(Modifier.fillMaxWidth().testTag("ai-web-source-preview").padding(horizontal = 16.dp).padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("站外来源", color = colors.fg, fontSize = 14.sp)
        Text(source.id.removePrefix("s"), Modifier.background(colors.surface2, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp), color = colors.fg2)
        Spacer(Modifier.weight(1f))
        Text(webReadLabel(web), color = colors.meta, fontSize = 12.sp)
      }
      Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).aiShadow(AiShadow.CARD, RoundedCornerShape(12.dp))
        .background(colors.surface, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          WebSourceIcon(12.dp)
          Text(webDomain(web.url), Modifier.weight(1f), color = colors.fg2, fontSize = 12.sp)
        }
        Text(web.title.ifBlank { webDomain(web.url) }, color = colors.fg, fontSize = 15.sp)
        Text(if (source.text.isBlank()) "本机不保存网页原文，内容以网站当前版本为准。" else source.text, color = colors.fg2, fontSize = 13.5.sp, lineHeight = 21.sp)
        Text(webReadState(web) + "。", color = colors.meta, fontSize = 12.sp)
        Text(web.url, color = colors.meta, fontSize = 11.5.sp)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(44.dp)) { Text("返回对话", color = colors.fg2) }
        Button(onClick = { onOpen(web.url) }, modifier = Modifier.weight(1.3f).height(44.dp),
          colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) { Text("打开网页") }
      }
    }
  }
}
