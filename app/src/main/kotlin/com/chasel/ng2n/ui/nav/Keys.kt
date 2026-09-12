package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.api.BoardKind
import kotlinx.serialization.Serializable

/**
 * 全 app 的导航键 —— RN 版 24 屏(`research/inventory.md` §1)一屏一个键。
 *
 * 键本身在这里**一次定齐**,屏幕由各票分别实现:没实现的键先注册成
 * [com.chasel.ng2n.ui.common.PlaceholderScreen],否则 Nav3 找不到条目会直接崩。
 * 这样票 17 只需要替换条目,不必回头改键的签名(键进了 back stack 的序列化形态,
 * 改签名等于让别人的屏幕跟着改)。
 *
 * 参数取值一律与 RN 版路由参数同名同义(`src/app/…` 的 `useLocalSearchParams`)。
 */

/*
 * 首页 `com.chasel.ng2n.ui.Home`、登录 `com.chasel.ng2n.ui.Login`、
 * 多账号 `com.chasel.ng2n.ui.Accounts` 三个键**留在 `ui/Ng2nApp.kt`** ——
 * 票 01 就在那儿声明了 Home,票 15 又在那儿加了 Login / Accounts。
 * 搬到这里只会让并行期的合并多三处冲突,而键住哪个文件对使用者没有区别。
 */

/** 版块 / 合集的主题列表。[id] 是「合集传 stid、普通版块传 fid」的那一个数(CONTEXT.md「合集」)。 */
@Serializable
data class BoardKey(
  val id: Long,
  val name: String? = null,
  val kind: BoardKind = BoardKind.BOARD,
  /** 版块页的三种形态:普通列表 / 24 小时热帖 / 精华区。RN 版是三个路由,这里合成一个键的三档 */
  val face: BoardFace = BoardFace.LIST,
) : NavKey

/** [BoardKey] 的三种面。 */
@Serializable
enum class BoardFace { LIST, HOT, RECOMMEND }

/** 子版块订阅 / 屏蔽管理。 */
@Serializable
data class SubBoardsKey(
  val id: Long,
  val name: String? = null,
  val kind: BoardKind = BoardKind.BOARD,
) : NavKey

/**
 * 主题详情(票 13 定义的签名,合并后统一落在这里)。
 * @param page 直接开在第几页(通知点进来时带)
 * @param pid 只看某一楼(「我的回复」/ pid 深链)
 * @param floor 进场就定位到第几楼(回复链的「在原帖中查看」)
 */
@Serializable
data class TopicKey(
  val tid: Long,
  val title: String? = null,
  val fav: String? = null,
  val page: Int? = null,
  val pid: Long? = null,
  val floor: Long? = null,
) : NavKey

// ---------------------------------------------------------------- 票 17(其余屏幕)

/** 搜索三 tab。`boardId`/`kind`/`boardName` 是从版块页带过去的「当前板块」选项。 */
@Serializable
data class SearchKey(
  val boardId: Long? = null,
  val kind: BoardKind = BoardKind.BOARD,
  val boardName: String? = null,
) : NavKey

/** 收藏的主题(一次一夹)。 */
@Serializable
data object FavoritesKey : NavKey

/** 收藏夹增删改。 */
@Serializable
data object FavoriteFoldersKey : NavKey

/** 浏览历史(纯本地)。 */
@Serializable
data object HistoryKey : NavKey

/** 离线帖子缓存管理。 */
@Serializable
data object CachesKey : NavKey

/** 屏蔽规则三 tab。 */
@Serializable
data object FiltersKey : NavKey

/** 通知(最近被喷)。 */
@Serializable
data object NotificationsKey : NavKey

/** 回复链(`/chain`)。从详情页某楼的引用块进来(票 13)。 */
@Serializable
data class ChainKey(
  val tid: Long,
  /** 展开起点的楼层 pid */
  val pid: Long,
  val fav: String? = null,
) : NavKey

/** 用户资料。 */
@Serializable
data class UserKey(val uid: Long, val name: String? = null) : NavKey

/** 我的主题 / 我的回复(同一屏,只差一个 kind)。 */
@Serializable
data class UserPostsKey(
  val uid: Long,
  val kind: UserPostKind,
  val name: String? = null,
) : NavKey

@Serializable
enum class UserPostKind { TOPICS, REPLIES }

/** 设置根。 */
@Serializable
data object SettingsKey : NavKey

/** 关于。 */
@Serializable
data object AboutKey : NavKey

/** 网页兜底 WebView(反封锁链外的第 6 步,用户手点)。 */
@Serializable
data class WebKey(val url: String, val title: String? = null) : NavKey
