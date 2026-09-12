package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.history.HISTORY_LIMIT
import com.chasel.ng2n.data.history.HistoryEntry
import com.chasel.ng2n.data.history.formatHistoryTime
import com.chasel.ng2n.data.history.historyProgressLabel
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

@Composable
fun HistoryScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val entries by deps.history.entries.collectAsStateWithLifecycle()
  val now by rememberMinuteTick()
  var clearOpen by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "浏览历史", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.DELETE_SWEEP,
        size = 22.dp,
        contentDescription = "清空浏览历史",
        onClick = { if (entries.isNotEmpty()) clearOpen = true },
      )
    }

    if (entries.isEmpty()) {
      EmptyState(icon = Ng2nIcon.HISTORY, text = "还没有浏览记录\n看过的主题会自动出现在这里")
      return@Column
    }

    LazyColumn(Modifier.fillMaxSize()) {
      item(key = ListKeys.SUB, contentType = "sub") {
        ListSubtitle("本机记录 · 保留最近 $HISTORY_LIMIT 条")
      }
      items(
        count = entries.size,
        key = { index -> entries[index].tid },
        contentType = { "history" },
      ) { index ->
        val entry = entries[index]
        HistoryRow(entry = entry, now = now) { nav.push(historyTopicKey(entry)) }
      }
      item(key = ListKeys.TAIL, contentType = "tail") { ListTail() }
    }
  }

  ConfirmDialog(
    open = clearOpen,
    title = "清空浏览历史",
    message = "${entries.size} 条本机记录将被删除。",
    confirmLabel = "清空",
    destructive = true,
    onCancel = { clearOpen = false },
    onConfirm = {
      clearOpen = false
      scope.launch {
        deps.history.clear()
        Snackbars.show("已清空浏览历史")
      }
    },
  )
}

internal fun historyTopicKey(entry: HistoryEntry): TopicKey = TopicKey(
  tid = entry.tid,
  title = entry.subject,
  fav = entry.favCode,
  floor = entry.lastFloor.takeIf { it >= 1 }?.toLong(),
)

@Composable
private fun HistoryRow(entry: HistoryEntry, now: Long, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  val title = remember(entry.subject, entry.boardName, colors) {
    buildAnnotatedString {
      append(entry.subject)
      val boardName = entry.boardName
      if (boardName != null) {
        withStyle(SpanStyle(color = colors.tag)) { append(" [$boardName]") }
      }
    }
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = entry.subject, onClick = onClick)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
  ) {
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
      AppIcon(icon = Ng2nIcon.PERSON, tint = colors.meta, size = 11.dp)
      Text(
        text = entry.author.orEmpty(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 118.dp),
        style = metaStyle(colors.link),
      )
      Spacer(Modifier.weight(1f))
      Text(text = formatHistoryTime(entry.updatedAt, now), style = metaStyle(colors.meta))
      Text(
        text = historyProgressLabel(entry.lastFloor, entry.maxFloor),
        style = metaStyle(colors.link),
      )
    }
  }
}

internal fun metaStyle(color: androidx.compose.ui.graphics.Color): TextStyle = TextStyle(
  fontSize = Typo.listMeta.size,
  lineHeight = 17.sp,
  color = color,
)
