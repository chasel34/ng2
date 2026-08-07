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
