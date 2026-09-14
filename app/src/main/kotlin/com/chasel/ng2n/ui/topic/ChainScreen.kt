package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.nav.ChainKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chasel.ng2n.core.local.ChainNode
import com.chasel.ng2n.core.local.ChainRole
import com.chasel.ng2n.ui.bbcode.BBCodeCallbacks
import com.chasel.ng2n.ui.bbcode.BBCodeContent
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalTextScale
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

private val INDENT_STEP = 14.dp

private const val MAX_INDENT_STEPS = 8

@Composable
fun ChainScreen(key: ChainKey, nav: Navigator) {
  val vm = rememberChainViewModel(key)
  val colors = LocalNg2nColors.current
  val textScale = LocalTextScale.current
  val aiSessions = com.chasel.ng2n.ui.ai.rememberAiSessions()
  var ai by remember { mutableStateOf(aiSessions.create()) }
  val aiState by ai.state.collectAsStateWithLifecycle()

  LaunchedEffect(colors, textScale, vm.settings.showSignature) {
    vm.applyStyle(
      TopicRenderStyle(
        colors = colors,
        bodyFontSize = textScale.bodyFontSize,
        bodyLineHeight = textScale.bodyLineHeight,
        showSignature = vm.settings.showSignature,
      ),
    )
  }

  val notAvailable = remember { { } }

  Column(
    Modifier
      .fillMaxSize()
      .background(rootBackground(colors, vm.settings.solidBackground)),
  ) {
    TopicTopBar {
      TopBarButton(onClick = nav::pop, label = "返回", box = 46.dp) {
        BackArrowIcon(tint = colors.onTopbar)
      }
      TopBarTitle(text = "回复链 · ${vm.chain.size} 层", modifier = Modifier.weight(1f))
      TopBarButton(onClick = {
        if (vm.chain.isNotEmpty()) {
          if (ai.state.value.conversationId != null) ai = aiSessions.create()
          ai.openChain(com.chasel.ng2n.data.topic.TopicPageParams(key.tid, 1, key.fav), vm.chain, vm.aiPages(), vm.startLou)
        }
      }, label = "AI 分析回复链") { AppIcon(Ng2nIcon.AUTO_AWESOME, colors.onTopbar) }
      TopBarButton(onClick = notAvailable, label = "回复") { ReplyIcon(tint = colors.onTopbar) }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        top = Spacing.row,
        start = Spacing.md,
        end = Spacing.md,
        bottom = 24.dp,
      ),
    ) {
      vm.startLou?.let { lou ->
        item(key = ListKeys.INTRO, contentType = "intro") {
          Text(
            text = "从第 $lou 楼展开:上游是它引用的楼层,下游是引用它的楼层。",
            fontSize = Typo.meta.size,
            color = colors.meta,
            modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.md),
          )
        }
      }
      itemsIndexedChain(vm = vm, nav = nav)
    }
  }
  com.chasel.ng2n.ui.ai.EntryAiSheet(aiState, ai, nav)
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedChain(
  vm: ChainViewModel,
  nav: Navigator,
) {
  items(
    items = vm.chain,
    key = { it.pid },
    contentType = { if (vm.entries.containsKey(it.pid)) "card" else "missing" },
  ) { node ->
    val index = vm.chain.indexOfFirst { it.pid == node.pid }.coerceAtLeast(0)
    val indent = INDENT_STEP * minOf(index, MAX_INDENT_STEPS)
    val entry = vm.entries[node.pid]
    if (entry == null) {
      MissingCard(
        node = node,
        indent = indent,
        loading = vm.isLoading(node.ref?.page?.toInt()),
        pageLoaded = vm.isPageLoaded(node.ref?.page?.toInt()),
        onRetry = { node.ref?.page?.let { vm.retryPage(it.toInt()) } },
        onOpenInTopic = vm.openInTopicKey(node)?.let { key -> { nav.push(key) } },
      )
    } else {
      ChainCard(
        entry = entry,
        current = node.role == ChainRole.CURRENT,
        indent = indent,
        onOpenInTopic = { vm.openInTopicKey(node)?.let(nav::push) },
      )
    }
  }
}

@Composable
private fun ChainCard(
  entry: ChainEntry,
  current: Boolean,
  indent: androidx.compose.ui.unit.Dp,
  onOpenInTopic: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .padding(start = indent, bottom = 10.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.lg))
      .background(if (current) colors.surface else colors.bg)
      .border(
        width = 1.5.dp,
        color = if (current) colors.primary else colors.divider,
        shape = RoundedCornerShape(Radius.lg),
      )
      .padding(horizontal = Spacing.row, vertical = 13.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      ChainAvatar(entry)
      Text(
        text = entry.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = Typo.notice.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.primary,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(
        text = "[${entry.lou} 楼] ${entry.postedAtText}",
        fontSize = Typo.meta.size,
        color = colors.meta,
        modifier = Modifier.weight(1f),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
      )
    }
    Box(Modifier.padding(top = 9.dp)) {
      BBCodeContent(model = entry.body, callbacks = BBCodeCallbacks())
    }
    Row(
      modifier = Modifier.padding(top = 9.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      if (current) {
        Text(
          text = "当前楼层",
          fontSize = Typo.meta.size,
          fontWeight = FontWeight.Bold,
          color = colors.primary,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.primaryContainer)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
      }
      Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ThumbUpIcon(tint = colors.meta, size = 14.dp)
        Text(entry.score.toString(), fontSize = Typo.meta.size, color = colors.meta)
      }
      Text(
        text = "在原帖中查看",
        fontSize = Typo.meta.size,
        fontWeight = FontWeight.SemiBold,
        color = colors.primary,
        modifier = Modifier
          .clip(RoundedCornerShape(Radius.xs))
          .clickable(onClick = onOpenInTopic)
          .padding(horizontal = Spacing.xs, vertical = 2.dp),
      )
    }
  }
}

@Composable
private fun ChainAvatar(entry: ChainEntry) {
  val colors = LocalNg2nColors.current
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier = Modifier.size(30.dp).clip(shape).background(entry.avatarColor),
    contentAlignment = Alignment.Center,
  ) {
    val url = entry.avatarUrl
    if (url == null) {
      Text(
        entry.avatarInitial,
        color = colors.onPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = Typo.meta.size,
      )
    } else {
      AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(30.dp),
      )
    }
  }
}

@Composable
private fun MissingCard(
  node: ChainNode,
  indent: androidx.compose.ui.unit.Dp,
  loading: Boolean,
  pageLoaded: Boolean,
  onRetry: () -> Unit,
  onOpenInTopic: (() -> Unit)?,
) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .padding(start = indent, bottom = 10.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(Radius.lg))
      .background(colors.bg)
      .border(1.5.dp, colors.divider, RoundedCornerShape(Radius.lg))
      .padding(horizontal = Spacing.row, vertical = 13.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      if (loading) {
        CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(16.dp))
      } else {
        CloudOffIcon(tint = colors.meta, size = 16.dp)
      }
      Text(
        text = when {
          loading -> "正在加载这一楼…"
          pageLoaded -> "这一楼没能加载:目标页里找不到它,可能已被删除"
          node.ref?.page == null -> "这一楼没能加载:引用里没有页码,定位不到"
          else -> "这一楼没能加载"
        },
        fontSize = Typo.listMeta.size,
        color = colors.meta,
        modifier = Modifier.weight(1f),
      )
      if (!loading && !pageLoaded && node.ref?.page != null) {
        Text(
          text = "重试",
          fontSize = Typo.meta.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.primary,
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .clickable(onClick = onRetry)
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
        )
      }
    }
    if (onOpenInTopic != null) {
      Row(
        modifier = Modifier.padding(top = 9.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Text(
          text = "在原帖中查看",
          fontSize = Typo.meta.size,
          fontWeight = FontWeight.SemiBold,
          color = colors.primary,
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .clickable(onClick = onOpenInTopic)
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
        )
      }
    }
  }
}
