package com.chasel.ng2n.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.bookmarks.BOOKMARK_NOTE_MAX
import com.chasel.ng2n.data.bookmarks.Bookmark
import com.chasel.ng2n.data.bookmarks.BookmarkDraft
import com.chasel.ng2n.data.bookmarks.BookmarkGroupRow
import com.chasel.ng2n.data.bookmarks.attachProgress
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.MenuItem
import com.chasel.ng2n.ui.common.OverflowMenu
import com.chasel.ng2n.ui.common.SnackbarAction
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.lists.ListSubtitle
import com.chasel.ng2n.ui.lists.ListTail
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

@Composable
fun BookmarksScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = deps.ioScope

  val groups by deps.bookmarks.observeGroups().collectAsStateWithLifecycle(emptyList())
  val history by deps.history.entries.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { deps.history.warmUp() }
  val rows = remember(groups, history) { attachProgress(groups, history) }
  val total = remember(groups) { groups.sumOf { it.bookmarks.size } }

  var clearOpen by remember { mutableStateOf(false) }
  var groupToDelete by remember { mutableStateOf<BookmarkGroupRow?>(null) }
  var menuTarget by remember { mutableStateOf<Bookmark?>(null) }
  var editing by remember { mutableStateOf<Bookmark?>(null) }

  fun openTopic(key: TopicKey) = nav.push(key)

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "书签", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.DELETE_SWEEP,
        size = 22.dp,
        contentDescription = "清空书签",
        onClick = { if (total > 0) clearOpen = true },
      )
    }

    if (rows.isEmpty()) {
      EmptyState(icon = Ng2nIcon.BOOKMARK_ADDED, text = "还没有书签\n在楼层菜单里选「加书签」，就会出现在这里")
      return@Column
    }

    LazyColumn(Modifier.fillMaxSize()) {
      item(key = ListKeys.SUB, contentType = "sub") {
        ListSubtitle("本机记录 · ${rows.size} 个主题 · $total 条书签")
      }
      rows.forEach { row ->
        val group = row.group
        item(key = "group-${group.tid}", contentType = "group") {
          GroupHeader(
            row = row,
            onOpen = {
              openTopic(TopicKey(tid = group.tid, title = group.subject, fav = group.favCode, floor = row.lastFloor))
            },
            onDelete = { groupToDelete = row },
          )
        }
        items(
          count = group.bookmarks.size,
          key = { index -> "bm-${group.tid}-${group.bookmarks[index].pid}" },
          contentType = { "bookmark" },
        ) { index ->
          val bookmark = group.bookmarks[index]
          BookmarkRow(
            bookmark = bookmark,
            onClick = {
              openTopic(
                TopicKey(
                  tid = bookmark.tid,
                  title = bookmark.subject,
                  fav = bookmark.favCode,
                  floor = bookmark.lou,
                  fromBookmark = true,
                ),
              )
            },
            onLongClick = { menuTarget = bookmark },
          )
        }
      }
      item(key = ListKeys.TAIL, contentType = "tail") { ListTail() }
    }
  }

  val target = menuTarget
  OverflowMenu(
    open = target != null,
    onDismiss = { menuTarget = null },
    items = if (target == null) {
      emptyList()
    } else {
      listOf(
        MenuItem("edit", "编辑备注") {
          menuTarget = null
          editing = target
        },
        MenuItem("delete", "删除书签") {
          menuTarget = null
          scope.launch {
            val removed = deps.bookmarks.remove(target.tid, target.pid) ?: return@launch
            Snackbars.show(
              "已删除书签",
              SnackbarAction("撤销") { scope.launch { deps.bookmarks.restore(removed) } },
            )
          }
        },
      )
    },
  )

  val edit = editing
  if (edit != null) {
    var noteLength by remember(edit) { mutableStateOf(edit.note.orEmpty().length) }
    InputDialog(
      open = true,
      title = "编辑备注",
      confirmLabel = "保存",
      hint = "$noteLength / $BOOKMARK_NOTE_MAX",
      initialValue = edit.note.orEmpty(),
      multiline = true,
      onValueChange = { noteLength = minOf(it.length, BOOKMARK_NOTE_MAX) },
      onCancel = { editing = null },
      onConfirm = { note ->
        editing = null
        scope.launch {
          deps.bookmarks.save(
            BookmarkDraft(
              tid = edit.tid,
              pid = edit.pid,
              lou = edit.lou,
              author = edit.author,
              summary = edit.summary,
              note = note,
              subject = edit.subject,
              boardName = edit.boardName,
              favCode = edit.favCode,
            ),
            nowSeconds = System.currentTimeMillis() / 1000,
          )
        }
      },
    )
  }

  val deleting = groupToDelete
  ConfirmDialog(
    open = deleting != null,
    title = "删除这个主题的书签",
    message = "${deleting?.group?.bookmarks?.size ?: 0} 条书签将被删除。",
    confirmLabel = "删除",
    destructive = true,
    onCancel = { groupToDelete = null },
    onConfirm = {
      groupToDelete = null
      val tid = deleting?.group?.tid ?: return@ConfirmDialog
      scope.launch { deps.bookmarks.removeTopic(tid) }
    },
  )

  ConfirmDialog(
    open = clearOpen,
    title = "清空书签",
    message = "$total 条书签将被删除。",
    confirmLabel = "清空",
    destructive = true,
    onCancel = { clearOpen = false },
    onConfirm = {
      clearOpen = false
      scope.launch {
        deps.bookmarks.clear()
        Snackbars.show("已清空书签")
      }
    },
  )
}

@Composable
private fun GroupHeader(row: BookmarkGroupRow, onOpen: () -> Unit, onDelete: () -> Unit) {
  val colors = LocalNg2nColors.current
  val group = row.group
  val title = remember(group.subject, group.boardName, colors) {
    buildAnnotatedString {
      append(group.subject)
      val boardName = group.boardName
      if (boardName != null) {
        withStyle(SpanStyle(color = colors.tag)) { append(" [$boardName]") }
      }
    }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface)
      .rowClickable(onClickLabel = group.subject, onClick = onOpen)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = 10.dp, start = Spacing.lg, end = Spacing.sm),
    verticalAlignment = Alignment.Top,
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
        modifier = Modifier.padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val lastFloor = row.lastFloor
        if (lastFloor != null) {
          AppIcon(icon = Ng2nIcon.BOOKMARK, tint = colors.link, size = 12.dp)
          Text(text = "上次读到 第 $lastFloor 楼", style = metaStyle(colors.link))
          Text(text = "·", style = metaStyle(colors.meta))
        }
        Text(text = "${group.bookmarks.size} 条书签", style = metaStyle(colors.meta))
      }
    }
    Box(
      modifier = Modifier
        .offset(y = (-6).dp)
        .size(40.dp)
        .clip(CircleShape)
        .clickable(onClickLabel = "删除这个主题的书签", onClick = onDelete)
        .semantics { contentDescription = "删除这个主题的书签" },
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(icon = Ng2nIcon.DELETE, tint = colors.meta, size = 19.dp)
    }
  }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, onClick: () -> Unit, onLongClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  val label = bookmark.note ?: bookmark.summary
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(
        onClickLabel = label,
        onLongClick = onLongClick,
        onLongClickLabel = "书签菜单",
        onClick = onClick,
      )
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = 11.dp, horizontal = Spacing.lg),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    AppIcon(
      icon = Ng2nIcon.BOOKMARK_ADDED,
      tint = colors.primary,
      size = 16.dp,
      modifier = Modifier.padding(top = 3.dp),
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      val note = bookmark.note
      if (note != null) {
        Text(text = note, maxLines = 1, overflow = TextOverflow.Ellipsis, style = primaryStyle(colors.fg))
        Text(text = bookmark.summary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = metaStyle(colors.fg2))
      } else {
        Text(text = bookmark.summary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = primaryStyle(colors.fg))
      }
      Text(
        text = buildAnnotatedString {
          withStyle(SpanStyle(color = colors.link)) { append("第 ${bookmark.lou} 楼") }
          withStyle(SpanStyle(color = colors.meta)) { append(" · ${bookmark.author}") }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = metaStyle(colors.meta),
      )
    }
  }
}

private fun primaryStyle(color: androidx.compose.ui.graphics.Color): TextStyle =
  TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = color)

private fun metaStyle(color: androidx.compose.ui.graphics.Color): TextStyle =
  TextStyle(fontSize = Typo.listMeta.size, lineHeight = 17.sp, color = color)
