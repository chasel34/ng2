/**
 * 搜索三合一（功能文档 §2.7）：主题、版块、用户。
 *
 * 三条路各打各的端点，**关键词编码还不一样**（API 文档 §0.5，2026-08-08 真机对拍）：
 *
 * - 主题：`thread.php?key=…`，key 按 **UTF-8** urlencode（配 `__inchst=UTF8`，
 *   fetcher 默认就带）；`fid`/`stid` 限定版块，`content=1` 连正文一起搜。
 * - 版块：`forum.php?key=…`，key 必须按 **GBK** urlencode——实测 UTF-8 的 key
 *   服务端按 GBK 解成乱码，直接「没找到符合条件的版面」。
 * - 用户：没有专门接口，纯数字按 uid、否则按用户名走 ucp 资料查询（user-profile.ts）。
 *
 * 主题结果与版块列表是同一形状（thread.php），解析全部复用 `parseTopicList`。
 */

import { NgaError, gbk, isRecord, type NgaFetcher } from '../net'
import { int, nonZero, orderedEntries, str } from './fields'
import { parseTopicList } from './topic-list'
import type { Board, TopicList } from './types'

export interface FetchTopicSearchOptions {
  /** 关键词（原文即可，UTF-8 编码由 query 层做） */
  readonly key: string
  /** 从 1 起 */
  readonly page: number
  /** 限定版块：合集传 stid、普通版块传 fid；不传 = 全站（API 文档 §2） */
  readonly boardId?: number
  readonly kind?: 'board' | 'collection'
  /** `content=1`：连正文一起搜。实测结果仍是普通主题行（没有 `__P`） */
  readonly searchContent?: boolean
  readonly signal?: AbortSignal
}

/**
 * 搜一页主题（`POST thread.php?key=…`）。
 *
 * 没有结果（或翻过头）时服务端回「2048:没有符合条件的结果」——假错误白名单里的
 * 一条，归一成空页；上层拿 `topics.length === 0` 停止翻页。
 * 实测三种参数组合（全站 / 本版 / 本版含正文）`__ROWS` 都是有效总数，总页数可信。
 */
export async function fetchTopicSearch(
  fetchNga: NgaFetcher,
  options: FetchTopicSearchOptions,
): Promise<TopicList> {
  const { key, page, boardId, kind, searchContent, signal } = options

  const result = await fetchNga({
    path: 'thread.php',
    query: {
      key,
      ...(boardId === undefined
        ? {}
        : kind === 'collection'
          ? { stid: boardId }
          : { fid: boardId }),
      ...(searchContent === true ? { content: 1 } : {}),
      page,
    },
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    if (result.fakeError !== undefined) return parseTopicList({})
    throw new NgaError({ kind: 'parse', message: '主题搜索响应里没有 data', via: result.via })
  }
  return parseTopicList(result.data)
}

/** 版块搜索的一条结果：版块本身 + 它挂在哪个上级版块下（结果行的来源标注）。 */
export interface BoardSearchItem {
  readonly board: Board
  readonly parentName?: string
}

/**
 * 解析 `forum.php?key=…` 的 `data`：条目直接以数字键挂在 data 上
 * （不像版块收藏包一层 `data["0"]`），每条形如
 * `{fid, stid, name, descrip, relevance, url, parent:{fid,name}}`。
 * 合集也会出现在结果里（`stid` 非 0，此时 `fid` 是宿主版块），身份规则与
 * 分类树同一条：stid 优先。没结果时 data 只剩 `__MESSAGE`，解出空数组。
 */
export function parseBoardSearch(data: unknown): BoardSearchItem[] {
  const items: BoardSearchItem[] = []
  for (const [, raw] of orderedEntries(data)) {
    if (!isRecord(raw)) continue
    const name = str(raw, 'name')
    if (name === undefined) continue

    const fid = nonZero(int(raw, 'fid'))
    const stid = nonZero(int(raw, 'stid'))
    const id = stid ?? fid
    if (id === undefined) continue

    const info = str(raw, 'descrip')
    const parentName = isRecord(raw.parent) ? str(raw.parent, 'name') : undefined
    items.push({
      board: {
        id,
        kind: stid === undefined ? 'board' : 'collection',
        ...(fid === undefined ? {} : { fid }),
        ...(stid === undefined ? {} : { stid }),
        name,
        ...(info === undefined ? {} : { info }),
      },
      ...(parentName === undefined ? {} : { parentName }),
    })
  }
  return items
}

export interface FetchBoardSearchOptions {
  /** 关键词（原文即可，**GBK** 编码由 query 层的 `gbk()` 标记触发） */
  readonly key: string
  readonly signal?: AbortSignal
}

/**
 * 搜版块（`POST forum.php?key=…`，key 走 GBK——API 文档 §1.2）。
 * 一次给全量（实测上限 100 条、按 relevance 排好），没有分页。
 * 「没找到符合条件的版面」在假错误白名单（`没找到`）里，归一成空数组。
 */
export async function fetchBoardSearch(
  fetchNga: NgaFetcher,
  options: FetchBoardSearchOptions,
): Promise<BoardSearchItem[]> {
  const { key, signal } = options

  const result = await fetchNga({
    path: 'forum.php',
    query: { key: gbk(key) },
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    if (result.fakeError !== undefined) return []
    throw new NgaError({ kind: 'parse', message: '版块搜索响应里没有 data', via: result.via })
  }
  return parseBoardSearch(result.data)
}

/** 用户搜索的查询方式：纯数字按 uid 查，否则按用户名查（功能文档 §2.7）。 */
export type UserSearchQuery =
  | { readonly kind: 'uid'; readonly uid: number }
  | { readonly kind: 'username'; readonly username: string }

/**
 * 把输入归一成 uid 或用户名。空输入（或全空白）返回 undefined。
 * 只有「整段都是数字」才算 uid——NGA 用户名可以带数字，混排的一律按名字查。
 */
export function parseUserSearchInput(text: string): UserSearchQuery | undefined {
  const trimmed = text.trim()
  if (trimmed === '') return undefined
  if (/^\d+$/.test(trimmed)) {
    const uid = Number(trimmed)
    if (Number.isSafeInteger(uid) && uid > 0) return { kind: 'uid', uid }
  }
  return { kind: 'username', username: trimmed }
}
