package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.HotTopicCandidate
import com.chasel.ng2n.core.local.TitleStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BoardKind {
  @SerialName("board")
  BOARD,

  @SerialName("collection")
  COLLECTION,
}

@Serializable
data class Board(
  /** 下游接口使用的标识，stid 优先于 fid。 */
  val id: Long,
  val kind: BoardKind,
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
  val info: String? = null,
  val iconUrl: String? = null,
  /** 版头主题的 tid。 */
  val head: Long? = null,
)

@Serializable
data class SubBoard(
  val id: Long,
  val kind: BoardKind,
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
  val info: String? = null,
  /** 订阅或屏蔽对象的 block tid，可能不同于版块 id。 */
  val filterId: Long,
  /** user_option 的 type：1 按 tid，0 按 fid；两者的 add/del 语义相反。 */
  val filterType: Int,
  val attributes: Long,
) {
  fun toBoard(): Board = Board(id = id, kind = kind, fid = fid, stid = stid, name = name, info = info)
}

@Serializable
data class BoardGroup(
  val id: String,
  val name: String,
  val boards: List<Board> = emptyList(),
)

@Serializable
data class BoardCategory(
  val id: String,
  val name: String,
  val groups: List<BoardGroup> = emptyList(),
)

@Serializable
data class HomeAnnouncement(
  val id: String,
  val title: String,
  val url: String? = null,
  /** 展示窗口的 Unix 秒时间戳；null 表示不限。 */
  val startAt: Long? = null,
  val endAt: Long? = null,
)

@Serializable
data class BoardTree(
  val categories: List<BoardCategory> = emptyList(),
  val announcements: List<HomeAnnouncement> = emptyList(),
)

@Serializable
data class TopicParent(
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
)

@Serializable
data class TopicShortcut(
  val kind: BoardKind,
  val id: Long,
)

@Serializable
data class TopicReply(
  val pid: Long,
  val content: String,
  /** Unix 秒时间戳。 */
  val postedAt: Long,
)

@Serializable
data class Topic(
  override val tid: Long,
  val fid: Long? = null,
  val subject: String,
  val titleStyle: TitleStyle = TitleStyle(),
  val author: String,
  val authorId: Long? = null,
  val anonymous: Boolean = false,
  val lastPoster: String? = null,
  override val replies: Long = 0,
  /** Unix 秒时间戳。 */
  override val postedAt: Long = 0,
  /** Unix 秒时间戳。 */
  override val lastPostAt: Long = 0,
  val favCode: String? = null,
  val locked: Boolean = false,
  val hasAttachment: Boolean = false,
  val isCollection: Boolean = false,
  val isBoardMirror: Boolean = false,
  override val shortcut: TopicShortcut? = null,
  val parent: TopicParent? = null,
  override val jumpUrl: String? = null,
  val reply: TopicReply? = null,
  val denied: Boolean = false,
) : HotTopicCandidate

@Serializable
enum class FloorClient {
  @SerialName("android")
  ANDROID,

  @SerialName("ios")
  IOS,

  @SerialName("other")
  OTHER,
}

@Serializable
data class FloorAttachment(
  val url: String,
  val thumbnailUrl: String? = null,
  val kind: String,
  val name: String? = null,
  val sizeKb: Long? = null,
)

@Serializable
data class FloorUser(
  /** 匿名用户带请求级前缀，避免不同页的负数 uid 冲突。 */
  val key: String,
  val uid: Long? = null,
  val name: String,
  /** 服务端原始用户名；匿名时保留 #anony_ 标识，用于识别楼主。 */
  val rawName: String,
  val anonymous: Boolean = false,
  val avatarUrl: String? = null,
  val level: String? = null,
  val signature: String? = null,
  val reputation: Double = 0.0,
  val postCount: Long = 0,
  val muted: Boolean = false,
  val nuked: Boolean = false,
)

@Serializable
data class Floor(
  val pid: Long = 0,
  val lou: Long = 0,
  val authorId: Long = 0,
  val authorKey: String,
  val isStarter: Boolean = false,
  val content: String,
  val subject: String? = null,
  /** Unix 秒时间戳。 */
  val postedAt: Long = 0,
  val postedAtText: String = "",
  val score: Long = 0,
  val edited: Boolean = false,
  val client: FloorClient = FloorClient.OTHER,
  val attachments: List<FloorAttachment> = emptyList(),
  val notes: List<Floor> = emptyList(),
  val vote: String? = null,
)

@Serializable
data class AdminForum(val fid: Long, val name: String)

@Serializable
data class ReputationEntry(
  val fid: Long,
  val name: String,
  val value: Long,
)

@Serializable
enum class UserStatus {
  @SerialName("active")
  ACTIVE,

  @SerialName("muted")
  MUTED,

  @SerialName("nuked")
  NUKED,
}

@Serializable
data class UserProfile(
  val uid: Long,
  val name: String,
  val avatarUrl: String? = null,
  val group: String? = null,
  val email: String? = null,
  val phone: String? = null,
  val postCount: Long = 0,
  /** 单位为铜。 */
  val money: Long = 0,
  val reputation: Double = 0.0,
  /** Unix 秒时间戳。 */
  val registeredAt: Long? = null,
  val ipLocation: String? = null,
  val status: UserStatus = UserStatus.ACTIVE,
  /** Unix 秒时间戳。 */
  val mutedUntil: Long? = null,
  val signature: String? = null,
  val adminForums: List<AdminForum> = emptyList(),
  val reputations: List<ReputationEntry> = emptyList(),
)

@Serializable
enum class TopicSource {
  @SerialName("native")
  NATIVE,

  @SerialName("web")
  WEB,

  @SerialName("cache")
  CACHE,
}

@Serializable
data class TopicDetail(
  val tid: Long = 0,
  val subject: String,
  val boardName: String? = null,
  val page: Int = 1,
  val totalRows: Long = 0,
  val rowsPerPage: Int = 20,
  val totalPages: Int = 1,
  val attachBase: String,
  val floors: List<Floor> = emptyList(),
  val hotReplies: List<Floor> = emptyList(),
  val users: Map<String, FloorUser> = emptyMap(),
  val source: TopicSource = TopicSource.NATIVE,
)

@Serializable
data class TopicList(
  val topics: List<Topic> = emptyList(),
  val board: Board? = null,
  val subBoards: List<SubBoard> = emptyList(),
  val totalRows: Long = 0,
  val rowsPerPage: Int = DEFAULT_TOPIC_ROWS_PER_PAGE,
  val totalPages: Int = 1,
  val listStructure: Boolean = false,
)

@Serializable
enum class NotificationKind {
  @SerialName("reply")
  REPLY,

  @SerialName("comment")
  COMMENT,

  @SerialName("mention")
  MENTION,

  @SerialName("message")
  MESSAGE,

  @SerialName("rating")
  RATING,

  @SerialName("other")
  OTHER,
}

@Serializable
data class NgaNotification(
  val id: String,
  val type: Int,
  val kind: NotificationKind,
  val userId: Long? = null,
  val userName: String,
  val subject: String,
  val tid: Long = 0,
  val pid: Long = 0,
  val myPid: Long? = null,
  /** Unix 秒时间戳。 */
  val timestamp: Long,
  val page: Int = 1,
)

@Serializable
data class NotificationFeed(
  val items: List<NgaNotification> = emptyList(),
  val serverUnread: Long? = null,
)
