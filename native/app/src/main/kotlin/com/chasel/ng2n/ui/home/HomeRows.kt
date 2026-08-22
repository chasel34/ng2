package com.chasel.ng2n.ui.home

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.HomeAnnouncement
import com.chasel.ng2n.ui.icons.Ng2nIcon

/**
 * 首页正文的行模型 —— 直译 RN 侧 `src/app/index.tsx` 的 `HomeRow`。
 *
 * **为什么要摊成行**:最大的分类(手机游戏)有 300 多个版块,一次铺完会连带发出
 * 三百多个图标请求。摊成「公告条 / 分组标题 / 版块格」交给 `LazyVerticalGrid`,
 * 只有滚到的那些才建。
 *
 * RN 侧把宫格摊成「一行三个」是因为 LegendList 只能虚拟化一维列表;
 * `LazyVerticalGrid` 本身就是二维的,所以这里一个版块就是一格,
 * 整行的项(公告、分组标题、空态)靠 span 撑满一行。
 */
sealed interface HomeRow {
  val key: String

  data class Announcement(
    override val key: String,
    val announcement: HomeAnnouncement,
  ) : HomeRow

  data class Group(
    override val key: String,
    val name: String,
    /** 组名圆章里的那个字 */
    val initial: String,
    /** 分组里的第一行:上方留的是宫格容器的 10,不是行距 14 */
    val first: Boolean,
  ) : HomeRow

  data class Cell(override val key: String, val board: Board) : HomeRow

  /** 空「我的收藏」的占位说明(游客引导登录、还没收藏都走它) */
  data class Notice(
    override val key: String,
    val icon: Ng2nIcon,
    val text: String,
    val actionLabel: String? = null,
  ) : HomeRow

  /** 收藏拉不下来。文案交给统一的错误组件,不在这儿把异常摊出来 */
  data class Failure(override val key: String, val error: Throwable?) : HomeRow
}

/** 「我的收藏」是个合成分类:id 用 `favorites/` 前缀,撞不上服务端那些裸词 id。 */
const val FAVORITES_CATEGORY_ID = "favorites/mine"

/** 设计稿这一组的圆章写的是「收」,不是组名首字「我」。 */
const val FAVORITES_INITIAL = "收"

/**
 * 服务端没有生效中的公告时显示的常驻提示 —— 文案取自设计稿首页。
 * 关掉后同样记进「已关闭」列表,不会再冒出来。
 */
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
  return rows
}

/**
 * 「我的收藏」tab 的行。收藏为空时**不画组标题**,只留一条说明 ——
 * 组标题下面空着一片会让人以为是没加载出来。
 */
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
  return rows
}
