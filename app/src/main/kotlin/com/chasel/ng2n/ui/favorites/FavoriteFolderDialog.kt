package com.chasel.ng2n.ui.favorites

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.account.AccountsState
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.favorites.FAVORITE_FOLDER_LIMIT
import com.chasel.ng2n.data.favorites.canCreateFavoriteFolder
import com.chasel.ng2n.data.favorites.favoriteFolderRows
import com.chasel.ng2n.data.favorites.planFavoriteApply
import com.chasel.ng2n.data.favorites.selectCreatedFolder
import com.chasel.ng2n.data.favorites.toggleFolderSelection
import com.chasel.ng2n.data.settings.foldersOfTopic
import com.chasel.ng2n.ui.common.DialogActions
import com.chasel.ng2n.ui.common.DialogShell
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.Motion
import com.chasel.ng2n.ui.common.MotionIcon
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.guardExitingOverlay
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

@Composable
fun FavoriteFolderDialog(open: Boolean, tid: Long, onClose: () -> Unit) {
  AnimatedVisibility(
    visible = open,
    enter = EnterTransition.None,
    exit = fadeOut(tween(Motion.DURATION_EXIT)),
    modifier = Modifier.guardExitingOverlay(open),
  ) {
    FolderPicker(tid = tid, onClose = onClose)
  }
}

@Composable
private fun FolderPicker(tid: Long, onClose: () -> Unit) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val accountsState: AccountsState? by deps.accounts.accounts
    .collectAsStateWithLifecycle(initialValue = null)
  val uidKnown = accountsState != null
  val uid = accountsState?.let { currentAccountOf(it)?.uid }

  val folderBuckets by deps.topicFavorites.folderStates.collectAsStateWithLifecycle()
  val foldersState = remember(folderBuckets, uid) { deps.topicFavorites.foldersOf(uid) }
  val folders = foldersState.folders

  var initial by remember { mutableStateOf<List<Int>?>(null) }
  var selected by remember { mutableStateOf<List<Int>>(emptyList()) }
  var busy by remember { mutableStateOf(false) }
  var creating by remember { mutableStateOf(false) }

  LaunchedEffect(uid, tid) {
    deps.topicFavorites.ensureFolders(uid)
  }
  LaunchedEffect(uid, tid, uidKnown) {
    if (!uidKnown) return@LaunchedEffect
    val known = foldersOfTopic(deps.topicFavorites.currentIndex(uid), tid)
    initial = known
    selected = known
  }

  fun confirm() {
    val before = initial ?: return
    val currentUid = uid ?: return
    val plan = planFavoriteApply(folders, before, selected)
    if (plan.isEmpty) {
      onClose()
      return
    }
    busy = true
    scope.launch {
      runCatching {
        deps.topicFavorites.applyTopicFavorites(
          uid = currentUid,
          tid = tid,
          added = plan.addedIds,
          removed = plan.removedIds,
        )
      }.fold(
        onSuccess = {
          busy = false
          Snackbars.show(plan.doneMessage)
          onClose()
        },
        onFailure = {
          busy = false
          Snackbars.show(failureText(it))
        },
      )
    }
  }

  fun createFolder(name: String) {
    val currentUid = uid ?: return
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      Snackbars.show("收藏夹名不能是空的")
      return
    }
    busy = true
    scope.launch {
      runCatching { deps.topicFavorites.createFolder(currentUid, trimmed) }.fold(
        onSuccess = { createdId ->
          busy = false
          creating = false
          selected = selectCreatedFolder(selected, createdId)
          Snackbars.show("已新建收藏夹「$trimmed」")
        },
        onFailure = {
          busy = false
          Snackbars.show(failureText(it))
        },
      )
    }
  }

  if (creating) {
    InputDialog(
      open = true,
      title = "新建收藏夹",
      hint = "最多 $FAVORITE_FOLDER_LIMIT 个收藏夹",
      confirmLabel = if (busy) "创建中…" else "创建",
      onCancel = { creating = false },
      onConfirm = ::createFolder,
    )
    return
  }

  DialogShell(open = true, onDismiss = onClose) {
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = Spacing.row)) {
      Text(
        text = "收藏到…",
        style = TextStyle(
          fontSize = Typo.dialogTitle.size,
          lineHeight = Typo.dialogTitle.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.fg,
        ),
      )

      when {
        !uidKnown || (foldersState.loading && !foldersState.loaded) ->
          DialogNotice("正在读收藏夹…")

        !foldersState.loaded -> Column(Modifier.padding(top = Spacing.md)) {
          DialogNotice(failureText(foldersState.error))
          Box(
            Modifier
              .padding(top = Spacing.sm)
              .align(Alignment.CenterHorizontally)
              .clickable { scope.launch { deps.topicFavorites.reloadFolders(uid) } }
              .padding(horizontal = Spacing.sm, vertical = 4.dp),
          ) {
            Text(
              text = "重试",
              style = TextStyle(
                fontSize = Typo.dialogAction.size,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
              ),
            )
          }
        }

        else -> Column(
          Modifier
            .padding(top = Spacing.md)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        ) {
          for (row in favoriteFolderRows(folders, selected)) {
            FolderCheckRow(
              name = row.name,
              subtitle = row.subtitle,
              checked = row.checked,
              enabled = !busy,
              onClick = { selected = toggleFolderSelection(selected, row.id) },
            )
          }
          if (canCreateFavoriteFolder(folders.size)) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(enabled = !busy) { creating = true },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
              AppIcon(icon = Ng2nIcon.ADD, tint = colors.accent, size = 21.dp)
              Text(
                text = "新建收藏夹…",
                style = TextStyle(
                  fontSize = Typo.dialogListItem.size,
                  lineHeight = Typo.dialogListItem.lineHeight,
                  color = colors.fg,
                ),
              )
            }
          }
          Text(
            text = "勾选状态取自本机记录，在网页版等别处收藏的帖子可能显示为未勾选。",
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            style = TextStyle(
              fontSize = Typo.cardMeta.size,
              lineHeight = 17.sp,
              color = colors.meta,
            ),
          )
        }
      }

      DialogActions(
        confirmLabel = if (busy) "保存中…" else "完成",
        destructive = false,
        onCancel = onClose,
        onConfirm = ::confirm,
        enabled = !busy && uidKnown && foldersState.loaded && initial != null,
      )
    }
  }
}

@Composable
private fun DialogNotice(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
    style = TextStyle(
      fontSize = Typo.notice.size,
      lineHeight = Typo.notice.lineHeight,
      color = colors.fg2,
    ),
  )
}

@Composable
private fun FolderCheckRow(
  name: String,
  subtitle: String,
  checked: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp)
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { contentDescription = if (checked) "$name，已勾选" else "$name，未勾选" },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    MotionIcon(
      icon = if (checked) Ng2nIcon.CHECK_BOX else Ng2nIcon.CHECK_BOX_OUTLINE_BLANK,
      tint = if (checked) colors.primary else colors.meta,
      size = 21.dp,
    )
    Column(Modifier.fillMaxWidth()) {
      Text(
        text = name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.dialogListItem.size,
          lineHeight = Typo.dialogListItem.lineHeight,
          color = colors.fg,
        ),
      )
      Text(
        text = subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.cardMeta.size,
          lineHeight = Typo.cardMeta.lineHeight,
          color = colors.meta,
        ),
      )
    }
  }
}
