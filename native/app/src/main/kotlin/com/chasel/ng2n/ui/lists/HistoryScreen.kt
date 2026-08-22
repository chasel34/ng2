package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
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
 * 浏览历史页 —— 直译 RN 侧 `src/app/history.tsx`(设计稿 `isSimpleList` 的 history 档)。
 *
 * 副标题条 + 行列表,右侧一格是阅读进度「读到 N 楼 / 读完」,点行重新打开主题。
 * **纯本地,零请求**:数据是 [com.chasel.ng2n.data.history.HistoryRepository] 内存里
 * 那份唯一事实来源(冷启动由 `StorageBootstrap` 在后台灌进去,首屏不等它)。
 */
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
      item(key = "sub", contentType = "sub") {
        ListSubtitle("本机记录 · 保留最近 $HISTORY_LIMIT 条")
      }
      items(
        count = entries.size,
        key = { index -> entries[index].tid },
        contentType = { "history" },
      ) { index ->
        val entry = entries[index]
        HistoryRow(entry = entry, now = now) {
          nav.push(TopicKey(tid = entry.tid, title = entry.subject, fav = entry.favCode))
        }
      }
      item(key = "tail", contentType = "tail") { ListTail() }
    }
  }

  // 设计稿是「立即清空 + 可撤销 toast」,但清空之后没有可撤销的对象(200 条记录
  // 已经从库里删了),RN 版为此退成先问一句 —— 这里照抄那个决定
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

/**
 * 历史的一行(设计稿:padding 14/16/12,下分隔线;信息行上距 9、gap 6、12.5 号)。
 *
 * 标题与后面那个灰色 `[版块名]` 拼成**一个** [buildAnnotatedString] ——
 * 分成两个 Text 的话窄屏上版块名会被甩到下一行的行首。
 */
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
      .clickable(onClickLabel = entry.subject, onClick = onClick)
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
      // 从非第 1 页进过来的主题拿不到楼主名,这一格留白
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

/** 信息行的行盒高度写死 17 —— 左右几段文字各自居中之后基线才不会错开。 */
internal fun metaStyle(color: androidx.compose.ui.graphics.Color): TextStyle = TextStyle(
  fontSize = Typo.listMeta.size,
  lineHeight = 17.sp,
  color = color,
)
