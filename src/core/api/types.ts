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
