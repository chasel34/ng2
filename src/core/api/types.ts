/**
 * 各接口的领域模型（术语见根目录 CONTEXT.md）。
 *
 * 全部是可 JSON 序列化的普通对象——版块树要原样写进本地缓存起底，
 * 不能带函数或 undefined 以外的特殊值。
 */

import type { TitleStyle } from '../local'

/**
 * 版块，或作为特殊版块的合集。
 *
 * `id` 是给下游接口用的那一个数字：**stid 优先于 fid**（CONTEXT.md「合集」，API 文档 §1.1），
 * `thread.php` 的 fid/stid 二选一也照这个规则取。`fid`/`stid` 两个原始值都保留，
 * 因为部分接口（子版块订阅、版块收藏）要按类型分别传。
 */
export interface Board {
  readonly id: number
  /** collection = 合集（有 stid），board = 普通版块 */
  readonly kind: 'collection' | 'board'
  readonly fid?: number
  readonly stid?: number
  readonly name: string
  /** 版块副标题，服务端可能不给 */
  readonly info?: string
  /** 远程图标；服务端图标清单里没登记的版块为 undefined，UI 直接走首字占位 */
  readonly iconUrl?: string
  /**
   * 版头（CONTEXT.md「版头」）主题的 tid，用普通详情页打开。
   * `thread.php` 的 `__F.topped_topic`（分类树接口偶尔也带，字段同名）；0/空串=没有。
   */
  readonly head?: number
}

/** 分类下的一组版块，对应设计稿宫格上方那行「⭕ 组名」。 */
export interface BoardGroup {
  readonly id: string
  readonly name: string
  readonly boards: readonly Board[]
}

/** 分类，对应首页顶部横向 tab 的一项。 */
export interface BoardCategory {
  /** 服务端的 `_id`，如 wow / other / new，稳定且适合当 tab key */
  readonly id: string
  readonly name: string
  readonly groups: readonly BoardGroup[]
}

/** 服务端下发的首页公告（`other.appcolumn_notis`）。 */
export interface HomeAnnouncement {
  /** 版本号 + 序号，用于记住「用户关掉过哪一条」 */
  readonly id: string
  readonly title: string
  readonly url?: string
  /** 展示窗口，秒级 unix 时间戳；缺省表示不限 */
  readonly startAt?: number
  readonly endAt?: number
}

export interface BoardTree {
  readonly categories: readonly BoardCategory[]
  readonly announcements: readonly HomeAnnouncement[]
}

/** 主题的来源子版块（`__T[].parent`），列表里显示成标题后面那个灰色 `[…]`。 */
export interface TopicParent {
  readonly fid?: number
  readonly stid?: number
  readonly name: string
}

/**
 * 快捷方式行：`type` 带 `0x8000`（合集）或 `0x200000`（版块镜像）的那种「主题」，
 * 点开是另一个版块的主题列表，不是一条讨论串。
 */
export interface TopicShortcut {
  readonly kind: 'board' | 'collection'
  readonly id: number
}

/**
 * 「某人的回复」列表里挂在主题上的那条回复（`__T[].__P`，API 文档 §2）。
 * 只有 `searchpost=1` 的请求才有，普通主题列表没有这个子对象。
 */
export interface TopicReply {
  readonly pid: number
  /** 回复正文 BBCode 原文 */
  readonly content: string
  /** 秒级 unix 时间戳；过期占位条目这里是 0 */
  readonly postedAt: number
}

/** 主题列表里的一行（CONTEXT.md「主题」）。 */
export interface Topic {
  /** 真实 tid：`quote_from` 非空时以它为准（API 文档 §2 解析要点 1） */
  readonly tid: number
  readonly fid?: number
  readonly subject: string
  readonly titleStyle: TitleStyle
  /** 已做匿名还原（CONTEXT.md「匿名还原」）的作者名 */
  readonly author: string
  /** 匿名主题没有数字 uid */
  readonly authorId?: number
  readonly anonymous: boolean
  readonly lastPoster?: string
  readonly replies: number
  /** 秒级 unix 时间戳 */
  readonly postedAt: number
  readonly lastPostAt: number
  /** fav 码（CONTEXT.md「fav 码」），从 `tpcurl` 提取，进详情页要带上 */
  readonly favCode?: string
  readonly locked: boolean
  readonly hasAttachment: boolean
  readonly isCollection: boolean
  readonly isBoardMirror: boolean
  readonly shortcut?: TopicShortcut
  readonly parent?: TopicParent
  /** 非 read.php 的外链主题（活动页），点了应该走浏览器 */
  readonly jumpUrl?: string
  /** `searchpost=1` 时挂在这一行上的那条回复 */
  readonly reply?: TopicReply
  /**
   * 服务端拒绝给内容（`denied:"1"`）：帖子过期或没权限看。
   * 「我的回复」列表末尾常有一串这种占位行，`subject` 就是拒绝理由。
   */
  readonly denied: boolean
}

/** 发帖设备（楼层的 `from_client`），设计稿在楼号前放一枚小图标。 */
export type FloorClient = 'android' | 'ios' | 'other'

/** 楼层里的一个附件（`attachs` 的成员）。地址已拼好，UI 直接用。 */
export interface FloorAttachment {
  /** 原图 */
  readonly url: string
  /** 缩略图；服务端没生成时为 undefined，宫格退回原图 */
  readonly thumbnailUrl?: string
  /** 服务端给的 `type`，目前只见过 `img` */
  readonly kind: string
  readonly name?: string
  /** 服务端给的 `size`，单位 KB */
  readonly sizeKb?: number
}

/**
 * 楼层作者。一次请求内 `key` 唯一——**匿名用户的 key 带请求级前缀**，
 * 否则第 2 页的 `-1` 会和第 1 页的 `-1` 串成同一个人（API 文档 §3）。
 */
export interface FloorUser {
  readonly key: string
  /** 匿名用户没有 */
  readonly uid?: number
  /** 显示名，匿名已还原成六字假名（CONTEXT.md「匿名还原」） */
  readonly name: string
  /** 服务端原始用户名，匿名时是 `#anony_<hex>`；认楼主要用它 */
  readonly rawName: string
  readonly anonymous: boolean
  readonly avatarUrl?: string
  /** 用户组名（设计稿的「级别」） */
  readonly level?: string
  /** 签名 BBCode（楼层菜单「查看签名」用）；没设置或空串时缺省 */
  readonly signature?: string
  /** 威望，已按服务端 `rvrc ÷ 10` 换算 */
  readonly reputation: number
  readonly postCount: number
  /** 禁言中（`buffs` 含 105/117） */
  readonly muted: boolean
  /** 被 nuke（`yz === -1`） */
  readonly nuked: boolean
}

/** 主题里的一条发言（CONTEXT.md「楼层」）。贴条与热门回复是同一个结构。 */
export interface Floor {
  readonly pid: number
  /** 楼层号，0 是主楼 */
  readonly lou: number
  /**
   * 服务端原始的 `authorid`。骰子种子要的就是这个数（CONTEXT.md「骰子」），
   * 匿名楼层这里是 `-1`、`-2` 这种页内序号而不是 uid——查人得走 `authorKey`。
   */
  readonly authorId: number
  /** 到 `TopicDetail.users` 里查作者 */
  readonly authorKey: string
  readonly isStarter: boolean
  /** 正文 BBCode 原文，渲染前过 `parseBBCode` */
  readonly content: string
  readonly subject?: string
  /** 秒级 unix 时间戳 */
  readonly postedAt: number
  /** 服务端排好的 `YYYY-MM-DD HH:mm`，省得客户端再格式化一遍 */
  readonly postedAtText: string
  /** 赞数 */
  readonly score: number
  readonly edited: boolean
  readonly client: FloorClient
  readonly attachments: readonly FloorAttachment[]
  /** 贴条（CONTEXT.md「贴条」），只有一层 */
  readonly notes: readonly Floor[]
  /** 投票原始串，渲染归 ticket 08 */
  readonly vote?: string
}

/** 用户在某个版面担任的职务（`adminForums`，管理权限卡一枚标签）。 */
export interface AdminForum {
  readonly fid: number
  readonly name: string
}

/** 某个版面的声望（`reputation`，声望条形图一行）。 */
export interface ReputationEntry {
  readonly fid: number
  /** 服务端只给 fid 时退回 `版面 <fid>` */
  readonly name: string
  readonly value: number
}

/** 账号状态（设计稿基础信息卡的「状态」一格）。 */
export type UserStatus = 'active' | 'muted' | 'nuked'

/**
 * 用户资料（`nuke.php?__lib=ucp&__act=get`，API 文档 §11.1）。
 *
 * 字段大半是可选的：同一个接口对不同用户吐的键差别很大——实测只有查自己时才有
 * `email`/`phone`，`adminForums`/`reputation` 只有真的担任职务/有声望的账号才有。
 * 缺了就不画那一格，别拿 0 和空串冒充数据。
 */
export interface UserProfile {
  readonly uid: number
  readonly name: string
  readonly avatarUrl?: string
  /** 用户组名（设计稿的「用户组」，楼层卡叫「级别」） */
  readonly group?: string
  /** 服务端已打码，形如 `we******@ng******` */
  readonly email?: string
  readonly phone?: string
  readonly postCount: number
  /** 铜币总数，拆金银铜用 `splitMoney` */
  readonly money: number
  /** 威望，已按 `rvrc ÷ 10` 换算 */
  readonly reputation: number
  /** 注册时间，秒级 unix 时间戳；老账号可能是 0（服务端没记） */
  readonly registeredAt?: number
  /** IP 属地；没有记录时服务端给「尚无记录」，这里保留原文 */
  readonly ipLocation?: string
  readonly status: UserStatus
  /** 禁言到期时间，秒级 unix 时间戳；未禁言为 undefined */
  readonly mutedUntil?: number
  /** 签名 BBCode 原文，渲染前过 `parseBBCode` */
  readonly signature?: string
  readonly adminForums: readonly AdminForum[]
  readonly reputations: readonly ReputationEntry[]
}

/** `read.php` 一页的结果。 */
export interface TopicDetail {
  readonly tid: number
  readonly subject: string
  readonly boardName?: string
  readonly page: number
  /** 楼层总数（含主楼），总页数按它算 */
  readonly totalRows: number
  readonly rowsPerPage: number
  readonly totalPages: number
  /** 附件图片基址，来自 `__GLOBAL._ATTACH_BASE_VIEW`，每页都可能变 */
  readonly attachBase: string
  readonly floors: readonly Floor[]
  /** 热门回复（CONTEXT.md），服务端只在主楼里标，独立成一区展示 */
  readonly hotReplies: readonly Floor[]
  readonly users: Readonly<Record<string, FloorUser>>
}

/** `thread.php` 一页的结果。 */
export interface TopicList {
  readonly topics: readonly Topic[]
  /** 当前版块（`__F`），进来时只有名字是已知的，这里能补上真身 */
  readonly board?: Board
  /** 子版块横条 */
  readonly subBoards: readonly Board[]
  readonly totalRows: number
  readonly rowsPerPage: number
  readonly totalPages: number
}

/**
 * 通知分类（API 文档 §9.1 的类型码归拢）：
 * 回复我的（1/2）、给我贴条的（3/4）、@我的（7/8）、短信类（10/11）、
 * 获评价（17），认不出的类型码进 `other`。
 */
export type NotificationKind = 'reply' | 'comment' | 'mention' | 'message' | 'rating' | 'other'

/** 一条通知（`nuke.php?__lib=noti`，API 文档 §9.1）。 */
export interface NgaNotification {
  /** 稳定 ID `时间戳-类型-tid-pid`（spec §4），本地已读模型靠它去重 */
  readonly id: string
  /** 原始类型码 */
  readonly type: number
  readonly kind: NotificationKind
  /** 对方 uid，短信类通知可能没有 */
  readonly userId?: number
  readonly userName: string
  /** 主题标题（短信类是会话标题） */
  readonly subject: string
  /** 短信类通知没有主题，tid/pid 记 0（稳定 ID 里也用 0 占位） */
  readonly tid: number
  /** 对方楼层的 pid */
  readonly pid: number
  /** 我的 pid（被回复/被贴条的那层） */
  readonly myPid?: number
  /** 秒级 unix 时间戳 */
  readonly timestamp: number
  /** 对方楼层所在页码，点通知跳这一页 */
  readonly page: number
}

/** `get_all` 一次拉回的整份通知。 */
export interface NotificationFeed {
  /** 三个容器合并后的条目，按时间戳降序 */
  readonly items: readonly NgaNotification[]
  /** 服务端的未读数——只作参考，本地已读模型不依赖它（服务端不提供逐条已读） */
  readonly serverUnread?: number
}
