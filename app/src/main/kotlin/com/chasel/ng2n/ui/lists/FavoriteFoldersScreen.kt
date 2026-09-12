package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.FavoriteFolder
import com.chasel.ng2n.data.account.EMPTY_ACCOUNTS
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.favorites.FAVORITE_FOLDER_LIMIT
import com.chasel.ng2n.ui.Login
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.SignedInGate
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.ListPullToRefreshBox
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.common.signedInGate
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

private sealed interface FolderDialog {
  data object Create : FolderDialog
  data class Rename(val folder: FavoriteFolder) : FolderDialog
  data class Delete(val folder: FavoriteFolder) : FolderDialog
}

private const val GUEST_PROMPT = "登录后才能管理云端收藏夹"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteFoldersScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = EMPTY_ACCOUNTS,
  )
  val uid = currentAccountOf(accountsState)?.uid

  val buckets by deps.topicFavorites.folderStates.collectAsStateWithLifecycle()
  val state = remember(buckets, uid) { deps.topicFavorites.foldersOf(uid) }
  val folders = state.folders

  var dialog by remember { mutableStateOf<FolderDialog?>(null) }
  var busy by remember { mutableStateOf(false) }

  LaunchedEffect(uid) { deps.topicFavorites.ensureFolders(uid) }

  fun run(done: String, action: suspend (String) -> Unit) {
    val currentUid = when (val gate = signedInGate(uid, GUEST_PROMPT)) {
      is SignedInGate.NeedLogin -> {
        dialog = null
        showLoginPrompt(nav, gate.message)
        return
      }

      is SignedInGate.Proceed -> gate.uid
    }
    busy = true
    scope.launch {
      runCatching { action(currentUid) }.fold(
        onSuccess = {
          dialog = null
          Snackbars.show(done)
        },
        onFailure = { Snackbars.show(failureText(it)) },
      )
      busy = false
    }
  }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "收藏夹管理", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.CREATE_NEW_FOLDER,
        size = 23.dp,
        contentDescription = "新建收藏夹",
        onClick = {
          if (folders.size >= FAVORITE_FOLDER_LIMIT) {
            Snackbars.show("最多 $FAVORITE_FOLDER_LIMIT 个收藏夹")
          } else {
            dialog = FolderDialog.Create
          }
        },
      )
    }

    when {
      uid == null -> EmptyState(
        icon = Ng2nIcon.PERSON_ADD,
        text = GUEST_PROMPT,
        action = StateAction("去登录") { nav.push(Login) },
      )

      state.loading && !state.loaded -> LoadingState()

      !state.loaded -> LoadFailedNotice(
        error = state.error,
        onRetry = { scope.launch { deps.topicFavorites.reloadFolders(uid) } },
        variant = StateVariant.SCREEN,
      )

      else -> ListPullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { scope.launch { deps.topicFavorites.reloadFolders(uid) } },
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = Spacing.md,
            bottom = 24.dp,
          ),
        ) {
          if (folders.isEmpty()) {
            item(key = ListKeys.EMPTY, contentType = "empty") {
              EmptyState(
                icon = Ng2nIcon.FOLDER,
                text = "还没有收藏夹，点右上角新建一个",
                variant = StateVariant.INLINE_HEAD,
              )
            }
          }
          items(
            count = folders.size,
            key = { index -> folders[index].id },
            contentType = { "folder" },
          ) { index ->
            val folder = folders[index]
            FolderCard(
              folder = folder,
              onRename = { dialog = FolderDialog.Rename(folder) },
              onSetDefault = {
                if (!folder.isDefault) {
                  run("已把「${folder.name}」设为默认收藏夹") { currentUid ->
                    deps.topicFavorites.modifyFolder(
                      uid = currentUid,
                      folderId = folder.id,
                      name = folder.name,
                      asDefault = true,
                    )
                  }
                }
              },
              onDelete = { dialog = FolderDialog.Delete(folder) },
            )
          }
          item(key = ListKeys.HINT, contentType = "hint") {
            Text(
              text = "收藏帖子时会弹出这份列表，一个主题可以同时归入多个收藏夹；" +
                "删掉收藏夹会连同夹里的收藏一起删掉，删了找不回来。",
              modifier = Modifier.padding(vertical = 6.dp, horizontal = Spacing.xs),
              style = TextStyle(
                fontSize = Typo.cardMeta.size,
                lineHeight = 19.2.sp,
                color = colors.meta,
              ),
            )
          }
        }
      }
    }
  }

  InputDialog(
    open = dialog is FolderDialog.Create,
    title = "新建收藏夹",
    hint = "最多 $FAVORITE_FOLDER_LIMIT 个收藏夹",
    confirmLabel = if (busy) "创建中…" else "创建",
    onCancel = { dialog = null },
    onConfirm = { name ->
      val trimmed = name.trim()
      if (trimmed.isEmpty()) {
        Snackbars.show("收藏夹名不能是空的")
      } else {
        run("已新建收藏夹「$trimmed」") { currentUid ->
          deps.topicFavorites.createFolder(currentUid, trimmed)
        }
      }
    },
  )

  val renaming = dialog as? FolderDialog.Rename
  InputDialog(
    open = renaming != null,
    title = "重命名收藏夹",
    initialValue = renaming?.folder?.name.orEmpty(),
    confirmLabel = if (busy) "保存中…" else "保存",
    onCancel = { dialog = null },
    onConfirm = { name ->
      val folder = renaming?.folder
      val trimmed = name.trim()
      if (folder == null || trimmed.isEmpty() || trimmed == folder.name) {
        dialog = null
      } else {
        run("已改名为「$trimmed」") { currentUid ->
          deps.topicFavorites.modifyFolder(currentUid, folder.id, trimmed)
        }
      }
    },
  )

  val deleting = dialog as? FolderDialog.Delete
  ConfirmDialog(
    open = deleting != null,
    title = "删除收藏夹",
    message = deleting?.let { "「${it.folder.name}」里的 ${it.folder.count} 个收藏会一起删掉，删了找不回来。" },
    confirmLabel = if (busy) "删除中…" else "删除",
    destructive = true,
    onCancel = { dialog = null },
    onConfirm = {
      val folder = deleting?.folder ?: return@ConfirmDialog
      run("已删除「${folder.name}」") { currentUid ->
        deps.topicFavorites.deleteFolder(currentUid, folder.id)
      }
    },
  )
}

@Composable
private fun FolderCard(
  folder: FavoriteFolder,
  onRename: () -> Unit,
  onSetDefault: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 10.dp)
      .clip(RoundedCornerShape(Radius.lg))
      .background(colors.surface)
      .border(1.dp, colors.divider, RoundedCornerShape(Radius.lg))
      .padding(Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    AppIcon(icon = Ng2nIcon.FOLDER, tint = colors.accent, size = 24.dp)
    Column(Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Text(
          text = folder.name,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
          style = TextStyle(
            fontSize = Typo.tab.size,
            lineHeight = Typo.tab.lineHeight,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg,
          ),
        )
        if (folder.isDefault) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(colors.primaryContainer)
              .padding(vertical = 2.dp, horizontal = 7.dp),
          ) {
            Text(
              text = "默认",
              style = TextStyle(
                fontSize = Typo.folderBadge.size,
                lineHeight = Typo.folderBadge.lineHeight,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
              ),
            )
          }
        }
      }
      Text(
        text = "${folder.count} 个主题",
        modifier = Modifier.padding(top = Spacing.xs),
        style = TextStyle(
          fontSize = Typo.cardMeta.size,
          lineHeight = Typo.cardMeta.lineHeight,
          color = colors.meta,
        ),
      )
    }
    CardAction(Ng2nIcon.EDIT, "重命名 ${folder.name}", colors.meta, onRename)
    CardAction(
      icon = Ng2nIcon.PUSH_PIN,
      label = "把 ${folder.name} 设为默认收藏夹",
      tint = if (folder.isDefault) colors.primary else colors.meta,
      onClick = onSetDefault,
    )
    CardAction(Ng2nIcon.DELETE, "删除 ${folder.name}", colors.danger, onDelete)
  }
}

@Composable
private fun CardAction(
  icon: Ng2nIcon,
  label: String,
  tint: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(Radius.full))
      .clickable(onClickLabel = label, onClick = onClick)
      .padding(Spacing.sm),
    contentAlignment = Alignment.Center,
  ) {
    AppIcon(icon = icon, tint = tint, size = 20.dp)
  }
}
