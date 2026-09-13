package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.api.BoardKind
import kotlinx.serialization.Serializable

@Serializable
data class BoardKey(
  val id: Long,
  val name: String? = null,
  val kind: BoardKind = BoardKind.BOARD,
  val face: BoardFace = BoardFace.LIST,
) : NavKey

@Serializable
enum class BoardFace { LIST, HOT, RECOMMEND }

@Serializable
data class SubBoardsKey(
  val id: Long,
  val name: String? = null,
  val kind: BoardKind = BoardKind.BOARD,
) : NavKey

@Serializable
data class TopicKey(
  val tid: Long,
  val title: String? = null,
  val fav: String? = null,
  val page: Int? = null,
  val pid: Long? = null,
  val floor: Long? = null,
  /** 从书签页进入：不弹「上次读到」横幅。 */
  val fromBookmark: Boolean = false,
) : NavKey

@Serializable
data class SearchKey(
  val boardId: Long? = null,
  val kind: BoardKind = BoardKind.BOARD,
  val boardName: String? = null,
) : NavKey

@Serializable
data object FavoritesKey : NavKey

@Serializable
data object FavoriteFoldersKey : NavKey

@Serializable
data object HistoryKey : NavKey

@Serializable
data object CachesKey : NavKey

@Serializable
data object BookmarksKey : NavKey

@Serializable
data object FiltersKey : NavKey

@Serializable
data object NotificationsKey : NavKey

@Serializable
data class ChainKey(
  val tid: Long,
  val pid: Long,
  val fav: String? = null,
) : NavKey

@Serializable
data class UserKey(val uid: Long, val name: String? = null) : NavKey

@Serializable
data class UserPostsKey(
  val uid: Long,
  val kind: UserPostKind,
  val name: String? = null,
) : NavKey

@Serializable
enum class UserPostKind { TOPICS, REPLIES }

@Serializable
data object SettingsKey : NavKey

@Serializable
data object AboutKey : NavKey

@Serializable
data class WebKey(val url: String, val title: String? = null) : NavKey
