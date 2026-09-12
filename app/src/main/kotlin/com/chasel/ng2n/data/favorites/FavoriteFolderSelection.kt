package com.chasel.ng2n.data.favorites

import com.chasel.ng2n.core.api.FavoriteFolder
import com.chasel.ng2n.data.settings.diffFolderSelection

data class FavoriteFolderRow(
  val id: Int,
  val name: String,
  val subtitle: String,
  val checked: Boolean,
)

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

fun toggleFolderSelection(selected: List<Int>, folderId: Int): List<Int> =
  if (folderId in selected) selected.filter { it != folderId } else selected + folderId

fun selectCreatedFolder(selected: List<Int>, createdId: Long?): List<Int> {
  val id = createdId?.toInt() ?: return selected
  return if (id in selected) selected else selected + id
}

fun canCreateFavoriteFolder(folderCount: Int): Boolean = folderCount < FAVORITE_FOLDER_LIMIT

data class FavoriteApplyPlan(
  val added: List<Int>,
  val removed: List<Int>,
  val doneMessage: String,
) {
  val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

  val addedIds: List<Long> get() = added.map { it.toLong() }
  val removedIds: List<Long> get() = removed.map { it.toLong() }
}

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

fun favoriteFolderName(folders: List<FavoriteFolder>, folderId: Int): String =
  folders.firstOrNull { it.id.toInt() == folderId }?.name ?: "收藏夹 $folderId"

fun favoriteResultText(added: List<String>, removed: List<String>): String {
  val parts = ArrayList<String>(2)
  if (added.isNotEmpty()) parts.add("已收藏到「${added.joinToString("、")}」")
  if (removed.isNotEmpty()) parts.add("已从「${removed.joinToString("、")}」移出")
  return parts.joinToString("；")
}

fun unfavoriteConfirmMessage(subject: String, folderName: String): String =
  "把「$subject」从「$folderName」里移出？其他收藏夹里的这一帖不受影响。"
