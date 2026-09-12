package com.chasel.ng2n.data.favorites

import com.chasel.ng2n.core.api.FavoriteFolder
import com.chasel.ng2n.data.settings.diffFolderSelection

/**
 * 「收藏到…」多选夹对话框的状态与差异计算 —— 直译 RN 侧 `ui/favorite-folder-dialog.tsx`
 * 里那几段夹在 JSX 中间的逻辑。
 *
 * 抽出来是因为**这几步全是纯计算**(勾选前后的差、要发哪几个请求、完成后那句话怎么说),
 * 而它们在 RN 版里长在组件里,只能靠人点着验。移过来一律做成纯函数,JVM 单测直接跑
 * (`FavoriteFolderSelectionTest`)。对话框本体只剩「画出来 + 把结果交给仓库」。
 *
 * 一条口径贯穿全文件:**夹 id 在本机索引里是 Int**(`TopicFavorIndex`),
 * 在接口与 [FavoriteFolder] 上是 Long。转换只发生在与仓库交界的那一处
 * ([FavoriteApplyPlan.addedIds] / [FavoriteApplyPlan.removedIds]),中间一律用 Int。
 */

/** 对话框列表里的一行(设计稿「收藏到…」的复选条目档,字号 `Typo.dialogListItem`)。 */
data class FavoriteFolderRow(
  val id: Int,
  val name: String,
  /** 副行:「N 个主题」,默认夹再缀一句 */
  val subtitle: String,
  val checked: Boolean,
)

/** 把夹列表 + 当前勾选摊成可直接渲染的行。 */
fun favoriteFolderRows(
  folders: List<FavoriteFolder>,
  selected: List<Int>,
): List<FavoriteFolderRow> {
  val checked = selected.toSet()
  return folders.map { folder ->
    val id = folder.id.toInt()
    FavoriteFolderRow(
      id = id,
      name = folder.name,
      subtitle = "${folder.count} 个主题" + if (folder.isDefault) " · 默认夹" else "",
      checked = id in checked,
    )
  }
}

/** 点一行:勾上/取消勾。原顺序不动,新勾的追加在末尾(差异计算本来就不看顺序)。 */
fun toggleFolderSelection(selected: List<Int>, folderId: Int): List<Int> =
  if (folderId in selected) selected.filter { it != folderId } else selected + folderId

/**
 * 新建夹之后顺手勾上 —— 用户点「新建收藏夹…」就是想把这帖收进去。
 * 服务端没回新夹 id(`createFavoriteFolder` 允许返回 null)时原样返回,不瞎猜。
 */
fun selectCreatedFolder(selected: List<Int>, createdId: Long?): List<Int> {
  val id = createdId?.toInt() ?: return selected
  return if (id in selected) selected else selected + id
}

/** 「新建收藏夹…」那一行还露不露:服务端的夹上限是 [FAVORITE_FOLDER_LIMIT]。 */
fun canCreateFavoriteFolder(folderCount: Int): Boolean = folderCount < FAVORITE_FOLDER_LIMIT

/**
 * 点「完成」要做的事。
 *
 * [added] / [removed] 就是 `diffFolderSelection` 的结果 ——**只对改动过的夹发请求**,
 * 没动过的一个都不发(重复 add 会把服务端的 `length` 算重)。
 * [doneMessage] 是全做成之后那句提示,现在就算好:做完再算的话,夹列表已经被重拉过,
 * 名字要是同时在别处改了就对不上了。
 */
data class FavoriteApplyPlan(
  val added: List<Int>,
  val removed: List<Int>,
  val doneMessage: String,
) {
  /** 什么都没动:直接关掉对话框,一个请求都不发。 */
  val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

  val addedIds: List<Long> get() = added.map { it.toLong() }
  val removedIds: List<Long> get() = removed.map { it.toLong() }
}

/** 勾选前后的差 + 完成提示语。 */
fun planFavoriteApply(
  folders: List<FavoriteFolder>,
  initial: List<Int>,
  selected: List<Int>,
): FavoriteApplyPlan {
  val diff = diffFolderSelection(initial, selected)
  return FavoriteApplyPlan(
    added = diff.added,
    removed = diff.removed,
    doneMessage = favoriteResultText(
      added = diff.added.map { favoriteFolderName(folders, it) },
      removed = diff.removed.map { favoriteFolderName(folders, it) },
    ),
  )
}

/**
 * 夹名。夹列表里找不到就退到「收藏夹 <id>」——
 * 本机索引记着的夹可能已经在别处被删了,提示语不能因此变成一句空引号。
 */
fun favoriteFolderName(folders: List<FavoriteFolder>, folderId: Int): String =
  folders.firstOrNull { it.id.toInt() == folderId }?.name ?: "收藏夹 $folderId"

/**
 * 完成后的提示语。设计稿是「已收藏到「默认收藏夹」」,取消收藏时换个说法;
 * 一次既加又减(把帖子从一个夹挪到另一个夹)时两句都说。
 */
fun favoriteResultText(added: List<String>, removed: List<String>): String {
  val parts = ArrayList<String>(2)
  if (added.isNotEmpty()) parts.add("已收藏到「${added.joinToString("、")}」")
  if (removed.isNotEmpty()) parts.add("已从「${removed.joinToString("、")}」移出")
  return parts.joinToString("；")
}

/** 收藏夹列表里「取消收藏」的确认文案(删的是这一夹里的这一条,不是整个夹)。 */
fun unfavoriteConfirmMessage(subject: String, folderName: String): String =
  "把「$subject」从「$folderName」里移出？其他收藏夹里的这一帖不受影响。"
