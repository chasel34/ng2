package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.HotTopicCandidate
import com.chasel.ng2n.core.local.TitleStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 各接口的领域模型(术语见根目录 CONTEXT.md)。直译 `src/core/api/types.ts`。
 *
 * 全部是 `@Serializable` 数据类:版块树要原样写进本地缓存起底、主题详情要整份进
 * 帖子缓存,不能带函数或不可序列化的东西。
 *
 * **字段名与金样本 `expected` 一一对应**——对拍是「Kotlin 结果序列化后与 expected 深比较」,
 * 改名等于改协议(`goldens/api/…`)。可选字段一律 `= null`:导出器把 `undefined` 的键
 * 整个删掉(goldens/README 规范 2),Kotlin 侧靠 `explicitNulls = false` 对上。
 */

/** 版块与合集共用的身份类型。合集 = 有 stid 的那一档(API 文档 §1.1「stid 优先于 fid」)。 */
@Serializable
enum class BoardKind {
  @SerialName("board")
  BOARD,

  @SerialName("collection")
  COLLECTION,
}

/**
 * 版块,或作为特殊版块的合集。
 *
 * [id] 是给下游接口用的那一个数字:**stid 优先于 fid**(CONTEXT.md「合集」,API 文档 §1.1),
 * `thread.php` 的 fid/stid 二选一也照这个规则取。[fid] / [stid] 两个原始值都保留,
 * 因为部分接口(子版块订阅、版块收藏)要按类型分别传。
 */
@Serializable
data class Board(
  val id: Long,
  val kind: BoardKind,
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
  /** 版块副标题,服务端可能不给 */
  val info: String? = null,
  /** 远程图标;服务端图标清单里没登记的版块为 null,UI 直接走首字占位 */
  val iconUrl: String? = null,
  /**
   * 版头(CONTEXT.md「版头」)主题的 tid,用普通详情页打开。
   * `thread.php` 的 `__F.topped_topic`(分类树接口偶尔也带,字段同名);0/空串 = 没有。
   */
  val head: Long? = null,
)

/**
 * 子版块(CONTEXT.md「子版块」):版块下的细分区,可订阅或屏蔽。
 *
 * 比普通版块多三个只有 `thread.php` 的 `__F.sub_forums` 才给的字段,
 * 判定与操作规则见 `SubBoard.kt`。TS 那边是 `interface SubBoard extends Board`,
 * Kotlin 的 data class 不能继承,所以身份字段在这里摊平——**金样本里它本来就是平的**。
 */
@Serializable
data class SubBoard(
  val id: Long,
  val kind: BoardKind,
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
  val info: String? = null,
  /**
   * 订阅/屏蔽操作的对象 id(`sub_forums` 的第 3 项,服务端管它叫 block tid,
   * 见 `info=add_to_block_tids`)。**不等于** fid/stid;缺这一项时退回版块 id。
   */
  val filterId: Long,
  /** `user_option` 的 `type`:有第 3 项 = 1(按 tid 屏蔽),没有 = 0(按 fid)。add/del 的语义按它反转 */
  val filterType: Int,
  /** 订阅状态码(`sub_forums` 的第 4 项),魔法数判定见 `SubBoard.kt` */
  val attributes: Long,
) {
  /** 当普通版块用(点进去看主题列表)。 */
  fun toBoard(): Board = Board(id = id, kind = kind, fid = fid, stid = stid, name = name, info = info)
}

/** 分类下的一组版块,对应设计稿宫格上方那行「⭕ 组名」。 */
@Serializable
data class BoardGroup(
  val id: String,
  val name: String,
  val boards: List<Board> = emptyList(),
)

/** 分类,对应首页顶部横向 tab 的一项。 */
@Serializable
data class BoardCategory(
  /** 服务端的 `_id`,如 wow / other / new,稳定且适合当 tab key */
  val id: String,
  val name: String,
  val groups: List<BoardGroup> = emptyList(),
)

/** 服务端下发的首页公告(`other.appcolumn_notis`)。 */
@Serializable
data class HomeAnnouncement(
  /** 版本号 + 序号,用于记住「用户关掉过哪一条」 */
  val id: String,
  val title: String,
  val url: String? = null,
  /** 展示窗口,秒级 unix 时间戳;缺省表示不限 */
  val startAt: Long? = null,
  val endAt: Long? = null,
)

@Serializable
data class BoardTree(
  val categories: List<BoardCategory> = emptyList(),
  val announcements: List<HomeAnnouncement> = emptyList(),
)

/** 主题的来源子版块(`__T[].parent`),列表里显示成标题后面那个灰色 `[…]`。 */
@Serializable
data class TopicParent(
  val fid: Long? = null,
  val stid: Long? = null,
  val name: String,
)

/**
 * 快捷方式行:`type` 带 `0x8000`(合集)或 `0x200000`(版块镜像)的那种「主题」,
 * 点开是另一个版块的主题列表,不是一条讨论串。
 */
@Serializable
data class TopicShortcut(
  val kind: BoardKind,
  val id: Long,
)

/**
 * 「某人的回复」列表里挂在主题上的那条回复(`__T[].__P`,API 文档 §2)。
 * 只有 `searchpost=1` 的请求才有,普通主题列表没有这个子对象。
 */
@Serializable
data class TopicReply(
  val pid: Long,
  /** 回复正文 BBCode 原文 */
  val content: String,
  /** 秒级 unix 时间戳;过期占位条目这里是 0 */
  val postedAt: Long,
)

/**
 * 主题列表里的一行(CONTEXT.md「主题」)。
 *
 * 实现 [HotTopicCandidate]:热帖聚合(票 10 的 `aggregateHotTopics`)拿它当输入,
 * 泛型保真——传 [Topic] 进去出来还是 [Topic]。
 */
@Serializable
data class Topic(
  /** 真实 tid:`quote_from` 非空时以它为准(API 文档 §2 解析要点 1) */
  override val tid: Long,
  val fid: Long? = null,
  val subject: String,
  val titleStyle: TitleStyle = TitleStyle(),
  /** 已做匿名还原(CONTEXT.md「匿名还原」)的作者名 */
  val author: String,
  /** 匿名主题没有数字 uid */
  val authorId: Long? = null,
  val anonymous: Boolean = false,
  val lastPoster: String? = null,
  override val replies: Long = 0,
  /** 秒级 unix 时间戳 */
  override val postedAt: Long = 0,
  override val lastPostAt: Long = 0,
  /** fav 码(CONTEXT.md「fav 码」),从 `tpcurl` 提取,进详情页要带上 */
  val favCode: String? = null,
  val locked: Boolean = false,
  val hasAttachment: Boolean = false,
  val isCollection: Boolean = false,
  val isBoardMirror: Boolean = false,
  override val shortcut: TopicShortcut? = null,
  val parent: TopicParent? = null,
  /** 非 read.php 的外链主题(活动页),点了应该走浏览器 */
  override val jumpUrl: String? = null,
  /** `searchpost=1` 时挂在这一行上的那条回复 */
  val reply: TopicReply? = null,
  /**
   * 服务端拒绝给内容(`denied:"1"`):帖子过期或没权限看。
   * 「我的回复」列表末尾常有一串这种占位行,[subject] 就是拒绝理由。
   */
  val denied: Boolean = false,
) : HotTopicCandidate

/** 发帖设备(楼层的 `from_client`),设计稿在楼号前放一枚小图标。 */
@Serializable
enum class FloorClient {
  @SerialName("android")
  ANDROID,

  @SerialName("ios")
  IOS,

  @SerialName("other")
  OTHER,
}

/** 楼层里的一个附件(`attachs` 的成员)。地址已拼好,UI 直接用。 */
@Serializable
data class FloorAttachment(
  /** 原图 */
  val url: String,
  /** 缩略图;服务端没生成时为 null,宫格退回原图 */
  val thumbnailUrl: String? = null,
  /** 服务端给的 `type`,目前只见过 `img` */
  val kind: String,
  val name: String? = null,
  /** 服务端给的 `size`,单位 KB */
  val sizeKb: Long? = null,
)

/**
 * 楼层作者。一次请求内 [key] 唯一——**匿名用户的 key 带请求级前缀**,
 * 否则第 2 页的 `-1` 会和第 1 页的 `-1` 串成同一个人(API 文档 §3)。
 */
@Serializable
data class FloorUser(
  val key: String,
  /** 匿名用户没有 */
  val uid: Long? = null,
  /** 显示名,匿名已还原成六字假名(CONTEXT.md「匿名还原」) */
  val name: String,
  /** 服务端原始用户名,匿名时是 `#anony_<hex>`;认楼主要用它 */
  val rawName: String,
  val anonymous: Boolean = false,
  val avatarUrl: String? = null,
  /** 用户组名(设计稿的「级别」) */
  val level: String? = null,
  /** 签名 BBCode(楼层菜单「查看签名」用);没设置或空串时缺省 */
  val signature: String? = null,
  /** 威望,已按服务端 `rvrc ÷ 10` 换算 */
  val reputation: Double = 0.0,
  val postCount: Long = 0,
  /** 禁言中(`buffs` 含 105/117) */
  val muted: Boolean = false,
  /** 被 nuke(`yz === -1`) */
  val nuked: Boolean = false,
)

/** 主题里的一条发言(CONTEXT.md「楼层」)。贴条与热门回复是同一个结构。 */
@Serializable
data class Floor(
  val pid: Long = 0,
  /** 楼层号,0 是主楼 */
  val lou: Long = 0,
  /**
   * 服务端原始的 `authorid`。骰子种子要的就是这个数(CONTEXT.md「骰子」),
   * 匿名楼层这里是 `-1`、`-2` 这种页内序号而不是 uid——查人得走 [authorKey]。
   */
  val authorId: Long = 0,
  /** 到 [TopicDetail.users] 里查作者 */
  val authorKey: String,
  val isStarter: Boolean = false,
  /**
   * 正文 BBCode **原文**。
   *
   * **这里不解析成 AST**(照抄 RN 版 `topic-detail.ts` 的边界):解析是票 13 在后台
   * 一次性预转换的事(anzong 四原则第一条),端点层解出来的东西要能原样进帖子缓存。
   */
  val content: String,
  val subject: String? = null,
  /** 秒级 unix 时间戳 */
  val postedAt: Long = 0,
  /** 服务端排好的 `YYYY-MM-DD HH:mm`,省得客户端再格式化一遍 */
  val postedAtText: String = "",
  /** 赞数 */
  val score: Long = 0,
  /** `alterinfo` 非空 = 被编辑过(API 文档 §3);内容是编辑记录,本票不展开 */
  val edited: Boolean = false,
  val client: FloorClient = FloorClient.OTHER,
  val attachments: List<FloorAttachment> = emptyList(),
  /** 贴条(CONTEXT.md「贴条」),只有一层 */
  val notes: List<Floor> = emptyList(),
  /** 投票原始串(`~` 分隔 kv),解析用票 10 的 `parseVote` */
  val vote: String? = null,
)

/** 用户在某个版面担任的职务(`adminForums`,管理权限卡一枚标签)。 */
@Serializable
data class AdminForum(val fid: Long, val name: String)

/** 某个版面的声望(`reputation`,声望条形图一行)。 */
@Serializable
data class ReputationEntry(
  val fid: Long,
  /** 服务端只给 fid 时退回 `版面 <fid>` */
  val name: String,
  val value: Long,
)

/** 账号状态(设计稿基础信息卡的「状态」一格)。 */
@Serializable
enum class UserStatus {
  @SerialName("active")
  ACTIVE,

  @SerialName("muted")
  MUTED,

  @SerialName("nuked")
  NUKED,
}

/**
 * 用户资料(`nuke.php?__lib=ucp&__act=get`,API 文档 §11.1)。
 *
 * 字段大半是可选的:同一个接口对不同用户吐的键差别很大——实测只有查自己时才有
 * `email`/`phone`,`adminForums`/`reputation` 只有真的担任职务/有声望的账号才有。
 * 缺了就不画那一格,别拿 0 和空串冒充数据。
 */
@Serializable
data class UserProfile(
  val uid: Long,
  val name: String,
  val avatarUrl: String? = null,
  /** 用户组名(设计稿的「用户组」,楼层卡叫「级别」) */
  val group: String? = null,
  /** 服务端已打码,形如 `we******@ng******` */
  val email: String? = null,
  val phone: String? = null,
  val postCount: Long = 0,
  /** 铜币总数,拆金银铜用票 10 的 `splitMoney` */
  val money: Long = 0,
  /** 威望,已按 `rvrc ÷ 10` 换算 */
  val reputation: Double = 0.0,
  /** 注册时间,秒级 unix 时间戳;老账号可能是 0(服务端没记) */
  val registeredAt: Long? = null,
  /** IP 属地;没有记录时服务端给「尚无记录」,那是占位不是属地,这里给 null */
  val ipLocation: String? = null,
  val status: UserStatus = UserStatus.ACTIVE,
  /** 禁言到期时间,秒级 unix 时间戳;未禁言为 null */
  val mutedUntil: Long? = null,
  /** 签名 BBCode 原文,渲染前过 `parseBBCode` */
  val signature: String? = null,
  val adminForums: List<AdminForum> = emptyList(),
  val reputations: List<ReputationEntry> = emptyList(),
)

/**
 * 这一页是从哪条路拿到的(ADR-0002)。`WEB` = 反封锁链的 Web 反解档出的产物,
 * `CACHE` = 帖子缓存档从本机还原的(断网/全档失败时的最后一道)。两者都要在
 * 详情页出一条数据源提示条(设计稿 fallbackBar)。
 */
@Serializable
enum class TopicSource {
  @SerialName("native")
  NATIVE,

  @SerialName("web")
  WEB,

  @SerialName("cache")
  CACHE,
}

/** `read.php` 一页的结果。 */
@Serializable
data class TopicDetail(
  val tid: Long = 0,
  val subject: String,
  val boardName: String? = null,
  val page: Int = 1,
  /** 楼层总数(含主楼),总页数按它算 */
  val totalRows: Long = 0,
  val rowsPerPage: Int = 20,
  val totalPages: Int = 1,
  /** 附件图片基址,来自 `__GLOBAL._ATTACH_BASE_VIEW`,每页都可能变 */
  val attachBase: String,
  val floors: List<Floor> = emptyList(),
  /** 热门回复(CONTEXT.md),服务端只在主楼里标,独立成一区展示 */
  val hotReplies: List<Floor> = emptyList(),
  val users: Map<String, FloorUser> = emptyMap(),
  val source: TopicSource = TopicSource.NATIVE,
)

/** `thread.php` 一页的结果。 */
@Serializable
data class TopicList(
  val topics: List<Topic> = emptyList(),
  /** 当前版块(`__F`),进来时只有名字是已知的,这里能补上真身 */
  val board: Board? = null,
  /** 子版块横条 */
  val subBoards: List<SubBoard> = emptyList(),
  val totalRows: Long = 0,
  val rowsPerPage: Int = DEFAULT_TOPIC_ROWS_PER_PAGE,
  val totalPages: Int = 1,
  /**
   * 服务端到底给没给「主题列表」这个结构(`__T`/`__F`/`__ROWS` 至少有一个)。
   *
   * **[topics] 为空时全靠它区分「这个版块真没帖」和「我们根本没拿到列表」**
   * (2026-08-13,「版块全空」排查:这两种当时共用同一句空态文案,
   * 用户没法从界面上看出自己是被限流了)。空版块服务端照样下发 `__T:{}` 与 `__F`。
   */
  val listStructure: Boolean = false,
)

/**
 * 通知分类(API 文档 §9.1 的类型码归拢):
 * 回复我的(1/2)、给我贴条的(3/4)、@我的(7/8)、短信类(10/11)、获评价(17),
 * 认不出的类型码进 [OTHER]。
 */
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

/** 一条通知(`nuke.php?__lib=noti`,API 文档 §9.1)。 */
@Serializable
data class NgaNotification(
  /** 稳定 ID `时间戳-类型-tid-pid`(spec §4),本地已读模型靠它去重 */
  val id: String,
  /** 原始类型码 */
  val type: Int,
  val kind: NotificationKind,
  /** 对方 uid,短信类通知可能没有 */
  val userId: Long? = null,
  val userName: String,
  /** 主题标题(短信类是会话标题) */
  val subject: String,
  /** 短信类通知没有主题,tid/pid 记 0(稳定 ID 里也用 0 占位) */
  val tid: Long = 0,
  /** 对方楼层的 pid */
  val pid: Long = 0,
  /** 我的 pid(被回复/被贴条的那层) */
  val myPid: Long? = null,
  /** 秒级 unix 时间戳 */
  val timestamp: Long,
  /** 对方楼层所在页码,点通知跳这一页 */
  val page: Int = 1,
)

/** `get_all` 一次拉回的整份通知。 */
@Serializable
data class NotificationFeed(
  /** 三个容器合并后的条目,按时间戳降序 */
  val items: List<NgaNotification> = emptyList(),
  /** 服务端的未读数——只作参考,本地已读模型不依赖它(服务端不提供逐条已读) */
  val serverUnread: Long? = null,
)
