/**
 * 版块分类树的领域模型（术语见根目录 CONTEXT.md）。
 *
 * 全部是可 JSON 序列化的普通对象——整棵树要原样写进本地缓存起底，
 * 不能带函数或 undefined 以外的特殊值。
 */

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
