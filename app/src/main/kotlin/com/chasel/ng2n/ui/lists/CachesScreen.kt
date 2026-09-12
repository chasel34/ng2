package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.cache.CachedTopic
import com.chasel.ng2n.data.cache.cachePagesLabel
import com.chasel.ng2n.data.cache.cacheTotalBytes
import com.chasel.ng2n.data.cache.formatCacheSize
import com.chasel.ng2n.data.history.formatHistoryTime
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/**
 * 「我的缓存」—— 直译 RN 侧 `src/app/caches.tsx`(设计稿 `isSimpleList` 的 cache 档)。
 *
 * 副标题报总占用,一行一个主题,右侧一格是体积、行尾是删除钮。点行离线打开 ——
 * 落在这个主题**已缓存的第一页**上,断网时反封锁链的缓存档会把它还原出来(ADR-0002)。
 *
 * 数据是 [com.chasel.ng2n.data.cache.TopicCacheRepository] 的**元数据**流:
 * 正文永远留在库里(一页十几万字符,几百页全灌进内存会把它吃光)。
 */
@Composable
fun CachesScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val topics by deps.topicCache.topics.collectAsStateWithLifecycle()
  val now by rememberMinuteTick()
  var clearOpen by remember { mutableStateOf(false) }
  val total = remember(topics) { cacheTotalBytes(topics) }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "我的缓存", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.DELETE_SWEEP,
        size = 22.dp,
        contentDescription = "清空全部缓存",
        onClick = { if (topics.isNotEmpty()) clearOpen = true },
      )
    }

    if (topics.isEmpty()) {
      EmptyState(
        icon = Ng2nIcon.CACHED,
        text = "还没有缓存的主题\n看过的帖子会自动存一份,断网时也能打开",
      )
      return@Column
    }

    LazyColumn(Modifier.fillMaxSize()) {
      item(key = ListKeys.SUB, contentType = "sub") {
        ListSubtitle("离线可读 · 已占用 ${formatCacheSize(total)}")
      }
      items(
        count = topics.size,
        key = { index -> topics[index].tid },
        contentType = { "cache" },
      ) { index ->
        val topic = topics[index]
        CacheRow(
          topic = topic,
          now = now,
          onClick = {
            // 落在已缓存的第一页上;夹里的 fav 码要带回去,不然隐藏/过期主题打不开
            nav.push(
              TopicKey(
                tid = topic.tid,
                title = topic.subject,
                fav = topic.favCode,
                page = topic.pages.firstOrNull() ?: 1,
              ),
            )
          },
          onDelete = {
            scope.launch {
              deps.topicCache.deleteTopic(topic.tid)
              Snackbars.show("已删除「${topic.subject}」的缓存")
            }
          },
        )
      }
      item(key = ListKeys.TAIL, contentType = "tail") { ListTail() }
    }
  }

  ConfirmDialog(
    open = clearOpen,
    title = "清空全部缓存",
    message = "${topics.size} 个主题、共 ${formatCacheSize(total)} 的离线数据将被删除。",
    confirmLabel = "清空",
    destructive = true,
    onCancel = { clearOpen = false },
    onConfirm = {
      clearOpen = false
      scope.launch {
        deps.topicCache.clear()
        Snackbars.show("已清空全部缓存")
      }
    },
  )
}

/** 缓存的一行(设计稿:与历史行同一套几何,行尾多一格删除钮)。 */
@Composable
private fun CacheRow(
  topic: CachedTopic,
  now: Long,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val title = remember(topic.subject, topic.boardName, colors) {
    buildAnnotatedString {
      append(topic.subject)
      val boardName = topic.boardName
      if (boardName != null) {
        withStyle(SpanStyle(color = colors.tag)) { append(" [$boardName]") }
      }
    }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = topic.subject, onClick = onClick)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = title,
        style = TextStyle(
          fontSize = Typo.listTitle.size,
          lineHeight = Typo.listTitle.lineHeight,
          color = colors.fg,
        ),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        AppIcon(icon = Ng2nIcon.DOWNLOAD, tint = colors.meta, size = 11.dp)
        Text(
          text = cachePagesLabel(topic),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthIn(max = 150.dp),
          style = metaStyle(colors.link),
        )
        Spacer(Modifier.weight(1f))
        Text(text = formatHistoryTime(topic.usedAt, now), style = metaStyle(colors.meta))
        Text(text = formatCacheSize(topic.bytes), style = metaStyle(colors.link))
      }
    }
    Box(
      modifier = Modifier
        .padding(start = Spacing.md)
        .clickable(onClickLabel = "删除「${topic.subject}」的缓存", onClick = onDelete)
        .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(icon = Ng2nIcon.DELETE, tint = colors.meta, size = 19.dp)
    }
  }
}
