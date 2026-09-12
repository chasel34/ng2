package com.chasel.ng2n.ui.home

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.HomeAnnouncement
import com.chasel.ng2n.ui.icons.Ng2nIcon

sealed interface HomeRow {
  val key: String

  data class Announcement(
    override val key: String,
    val announcement: HomeAnnouncement,
  ) : HomeRow

  data class Group(
    override val key: String,
    val name: String,
    val initial: String,
    val first: Boolean,
  ) : HomeRow

  data class Cell(override val key: String, val board: Board) : HomeRow

  data class Notice(
    override val key: String,
    val icon: Ng2nIcon,
    val text: String,
    val actionLabel: String? = null,
  ) : HomeRow

  data class Failure(override val key: String, val error: Throwable?) : HomeRow
}

const val FAVORITES_CATEGORY_ID = "favorites/mine"

const val FAVORITES_INITIAL = "收"

val BUILTIN_ANNOUNCEMENT = HomeAnnouncement(
  id = "builtin/multi-account",
  title = "建议登录多个账号，可有效改善跳转系统浏览器的问题",
)

private fun pushBoards(rows: MutableList<HomeRow>, groupId: String, boards: List<Board>) {
  boards.forEach { board -> rows += HomeRow.Cell("cell/$groupId/${board.id}", board) }
}

fun buildHomeRows(category: BoardCategory, announcement: HomeAnnouncement?): List<HomeRow> {
  val rows = ArrayList<HomeRow>()
  if (announcement != null) {
    rows += HomeRow.Announcement("announcement/${announcement.id}", announcement)
  }
  category.groups.forEachIndexed { index, group ->
    rows += HomeRow.Group(
      key = "group/${group.id}",
      name = group.name,
      initial = initialOf(group.name),
      first = index == 0,
    )
    pushBoards(rows, group.id, group.boards)
  }
  return rows.distinctByKey()
}

fun buildFavoriteRows(
  announcement: HomeAnnouncement?,
  boards: List<Board>,
  placeholder: HomeRow,
): List<HomeRow> {
  val rows = ArrayList<HomeRow>()
  if (announcement != null) {
    rows += HomeRow.Announcement("announcement/${announcement.id}", announcement)
  }
  if (boards.isEmpty()) {
    rows += placeholder
    return rows
  }
  rows += HomeRow.Group("group/favorites", "我的收藏", FAVORITES_INITIAL, first = true)
  pushBoards(rows, "favorites", boards)
  return rows.distinctByKey()
}

private fun List<HomeRow>.distinctByKey(): List<HomeRow> = distinctBy { it.key }
