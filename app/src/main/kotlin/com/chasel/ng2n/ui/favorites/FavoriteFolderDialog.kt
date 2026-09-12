package com.chasel.ng2n.ui.favorites

import androidx.compose.foundation.background
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
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/**
 * 「收藏到…」多选收藏夹对话框(设计稿 `dialog:'folder'`)——
 * 直译 RN 侧 `src/ui/favorite-folder-dialog.tsx`。
 *
 * **传 tid 即可从任意入口调起**:主题详情顶栏菜单「收藏本帖」与楼层菜单「收藏」用的
 * 是同一个调用(票 33 把这两处从桩 Toast 换成了它)。
 *
 * 关着的时候整棵子树不挂载([DialogShell] 的 `open` 为假直接 return),所以不会在每次
 * 进详情页时白打一发 `list_folder` —— 与 RN 版同一条边界。
 *
 * ## 状态逻辑都在 `data/favorites/FavoriteFolderSelection.kt`
 *
 * 勾选切换、差异计算、完成提示语、「新建收藏夹…」露不露,全是那边的纯函数,
 * JVM 单测直接跑。这里只负责画,以及把结果交给
 * [com.chasel.ng2n.data.favorites.TopicFavoriteRepository.applyTopicFavorites]。
 *
 * ## 游客态不由这里挡
 *
 * 调用方先用 [com.chasel.ng2n.ui.common.showLoginPrompt] 挡住(与抽屉、版块收藏那些入口
 * 同一套「登录后才能…」+「去登录」提示条),对话框只在已登录时才开:
 * 接口对游客一律回「你必须先登录论坛」,开出来也只是一个必然失败的空列表。
 */
@Composable
fun FavoriteFolderDialog(open: Boolean, tid: Long, onClose: () -> Unit) {
  if (!open) return
  FolderPicker(tid = tid, onClose = onClose)
}

@Composable
private fun FolderPicker(tid: Long, onClose: () -> Unit) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  // 登录态三态:还没从磁盘读到(null)/ 游客 / 某个 uid。账号表是 DataStore,
  // 第一次发射必然晚于首帧 —— 少了这一档,已登录用户会先看到一格「一个夹都没有」
  val accountsState: AccountsState? by deps.accounts.accounts
    .collectAsStateWithLifecycle(initialValue = null)
  val uidKnown = accountsState != null
  val uid = accountsState?.let { currentAccountOf(it)?.uid }

  val folderBuckets by deps.topicFavorites.folderStates.collectAsStateWithLifecycle()
  val foldersState = remember(folderBuckets, uid) { deps.topicFavorites.foldersOf(uid) }
  val folders = foldersState.folders

  // 打开这一刻的归属就是「改动前」的基准;之后勾选只动 selected,不跟着索引跑
  // (写成功后仓库会改索引,跟着跑的话第二次点「完成」会把刚做的事又算一遍差)。
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
        // 串行写到一半失败:已做成的那几个不回滚,提示里把服务端的话原样带出来
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
          // 新建完顺手勾上 —— 用户点「新建收藏夹…」就是想把这帖收进去
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

  // 「新建收藏夹」在设计稿里是同一个对话框槽位的另一个形态,所以整面板换掉而不是叠一层
  // (叠着的话两层遮罩会把底下压得死黑)。勾选状态留在本组件里,建完就回到多选。
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
            // 夹多了也不让对话框顶到屏幕外,列表自己滚(RN 版 maxHeight 320)
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
          // 勾选状态是本机攒的(服务端给不出反查),这层限制得跟用户说清楚
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
        // 夹列表还没回来就没法算差异,「完成」这时候点了只会是个空动作
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

/** 一行复选夹条目(设计稿「收藏到…」那档:21 见方的方框 + 名字 + 副行)。 */
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
    AppIcon(
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
