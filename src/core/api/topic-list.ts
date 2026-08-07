/**
 * 主题列表的解析（API 文档 §2）。
 *
 * `thread.php` 一个端点覆盖版块列表 / 搜索 / 收藏夹 / 某人的主题，响应形状都一样：
 *
 * ```jsonc
 * { "data": {
 *     "__T":  { "0": { "tid": 1, "subject": "…", "type": 8192, "parent": { "0": 275, "2": "父版面名" } } },
 *     "__F":  { "fid": -7, "name": "网事杂谈", "topped_topic": 3593852, "sub_forums": { … } },
 *     "__ROWS": 10008547, "__T__ROWS_PAGE": 35 } }
 * ```
 *
 * 这里的坑比分类树多一档：**同一个字段会换类型**（`parent` 2024-04 从对象变成字符串化
 * JSON，`replies` 有时是数字有时是字符串）。所以每个字段都单独容错，坏了就退到缺省值，
 * 绝不让一条主题带崩整页——被封时这一页是用户唯一能看到的东西。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { decodeTitleStyle, isAnonymousAuthor, parseTopicMisc, resolveAuthorName } from '../local'
import { int, nonZero, orderedValues, str } from './fields'
import type { Board, Topic, TopicList, TopicParent, TopicShortcut } from './types'

/** `type` 位掩码（API 文档 §2 解析要点 3，取自 NGA 官方前端的 PB 表）。 */
const TYPE_LOCKED = 1024
const TYPE_ATTACHMENT = 8192
const TYPE_COLLECTION = 0x8000
const TYPE_BOARD_MIRROR = 0x200000

/** 服务端不给页大小时的缺省值：NGA 的主题列表固定 35 条一页。 */
const DEFAULT_ROWS_PER_PAGE = 35

/** 没标题的主题（NGA 允许）在列表里的占位。 */
const UNTITLED = '无标题'

/** fav 码（CONTEXT.md「fav 码」）藏在 `tpcurl` 的 query 里。 */
const FAV_PATTERN = /[?&]fav=([0-9a-fA-F]+)/
const TPCURL_TID_PATTERN = /[?&]tid=(\d+)/

/**
 * `parent` 有两种形态：对象 `{0:fid,1:stid,2:name}`，以及 2024-04 之后的字符串化 JSON。
 * 名字解不出来就当没有——只有名字是要显示的，光有个 id 对用户没意义。
 */
function parseParent(raw: unknown): TopicParent | undefined {
  let value = raw
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value) as unknown
    } catch {
      return undefined
    }
  }
  if (!isRecord(value)) return undefined

  const name = str(value, '2')
  if (name === undefined) return undefined
  const fid = nonZero(int(value, '0'))
  const stid = nonZero(int(value, '1'))

  return {
    ...(fid === undefined ? {} : { fid }),
    ...(stid === undefined ? {} : { stid }),
    name,
  }
}

/**
 * 快捷方式行指向哪儿。合集的 stid 就是它自己的 tid（合集本身是一个主题），
 * 版块镜像的目标 fid 在 `topic_misc` / `topic_misc_var` 里（API 文档 §2 解析要点 3）。
 */
function parseShortcut(
  type: number,
  ids: { tid: number; fid?: number; stid?: number; sfid?: number },
): TopicShortcut | undefined {
  if (type & TYPE_COLLECTION) {
    return { kind: 'collection', id: ids.stid ?? ids.tid }
  }
  if (type & TYPE_BOARD_MIRROR) {
    const id = ids.sfid ?? ids.fid
    return id === undefined ? undefined : { kind: 'board', id }
  }
  return undefined
}

function parseTopic(raw: unknown): Topic | undefined {
  if (!isRecord(raw)) return undefined

  const tpcurl = str(raw, 'tpcurl')
  // 真实 tid 看 quote_from（API 文档 §2 解析要点 1）：quote_from 非 0 时
  // tid 字段反而是引用来源。两个都没有就退回 tpcurl 里的那个。
  const tid =
    nonZero(int(raw, 'quote_from')) ??
    nonZero(int(raw, 'tid')) ??
    (tpcurl === undefined ? undefined : Number(TPCURL_TID_PATTERN.exec(tpcurl)?.[1]))
  if (tid === undefined || !Number.isFinite(tid)) return undefined

  const misc = parseTopicMisc(raw.topic_misc)
  // topic_misc_var 是服务端预解析好的同一份东西，topic_misc 是空串时靠它兜底
  const miscVar = isRecord(raw.topic_misc_var) ? raw.topic_misc_var : undefined
  const stid = misc.stid ?? (miscVar === undefined ? undefined : nonZero(int(miscVar, '2')))
  const sfid = misc.sfid ?? (miscVar === undefined ? undefined : nonZero(int(miscVar, '3')))

  const type = int(raw, 'type') ?? 0
  const fid = nonZero(int(raw, 'fid'))
  const rawAuthor = str(raw, 'author') ?? ''
  const favCode = tpcurl === undefined ? undefined : FAV_PATTERN.exec(tpcurl)?.[1]
  const parent = parseParent(raw.parent)
  const jumpUrl = str(raw, 'jumpurl')
  const authorId = nonZero(int(raw, 'authorid'))
  const lastPoster = str(raw, 'lastposter')
  const shortcut = parseShortcut(type, { tid, fid, stid, sfid })

  return {
    tid,
    ...(fid === undefined ? {} : { fid }),
    subject: str(raw, 'subject') ?? UNTITLED,
    titleStyle: decodeTitleStyle({ titlefont: raw.titlefont, topicMisc: raw.topic_misc }),
    author: resolveAuthorName(rawAuthor),
    ...(authorId === undefined ? {} : { authorId }),
    anonymous: isAnonymousAuthor(rawAuthor),
    ...(lastPoster === undefined ? {} : { lastPoster: resolveAuthorName(lastPoster) }),
    replies: int(raw, 'replies') ?? 0,
    postedAt: int(raw, 'postdate') ?? 0,
    lastPostAt: int(raw, 'lastpost') ?? 0,
    ...(favCode === undefined ? {} : { favCode }),
    locked: (type & TYPE_LOCKED) !== 0,
    hasAttachment: (type & TYPE_ATTACHMENT) !== 0,
    isCollection: (type & TYPE_COLLECTION) !== 0,
    isBoardMirror: (type & TYPE_BOARD_MIRROR) !== 0,
    ...(shortcut === undefined ? {} : { shortcut }),
    ...(parent === undefined ? {} : { parent }),
    ...(jumpUrl === undefined ? {} : { jumpUrl }),
  }
}

/**
 * 版块身份：**stid 优先于 fid**（CONTEXT.md「合集」）。`__F` 与 `sub_forums` 两处
 * 的字段名完全不同，但认 id 的规则得是同一条，所以两边都从这里出。
 */
function boardIdentity(
  name: string,
  ids: { fid?: number; stid?: number },
): Board | undefined {
  const id = ids.stid ?? ids.fid
  if (id === undefined) return undefined
  return {
    id,
    kind: ids.stid === undefined ? 'board' : 'collection',
    ...(ids.fid === undefined ? {} : { fid: ids.fid }),
    ...(ids.stid === undefined ? {} : { stid: ids.stid }),
    name,
  }
}

/**
 * 子版块（`__F.sub_forums`）：值是 `{0:id, 1:名字, 2:副标题, 3:?, 4:订阅状态码}`，
 * **key 以 `t` 开头表示这是合集**（值里的 id 是 stid，见调研报告 §2）。
 */
function parseSubBoard(key: string, raw: unknown): Board | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, '1')
  const id = nonZero(int(raw, '0'))
  if (name === undefined || id === undefined) return undefined

  const board = boardIdentity(name, key.startsWith('t') ? { stid: id } : { fid: id })
  const info = str(raw, '2')
  return board === undefined ? undefined : { ...board, ...(info === undefined ? {} : { info }) }
}

/** 当前版块（`__F`）。 */
function parseBoard(raw: unknown): Board | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined
  return boardIdentity(name, {
    fid: nonZero(int(raw, 'fid')),
    stid: nonZero(int(raw, 'stid')),
  })
}

/**
 * 解一页主题列表。传的是响应的 `data`。
 *
 * 整页解不出来也不抛：主题列表是无限滚动的，某一页坏掉应该只是"这页没东西"，
 * 上层拿 `topics.length === 0` 判断要不要停止翻页。
 */
export function parseTopicList(data: unknown): TopicList {
  const root = isRecord(data) ? data : {}

  const topics = orderedValues(root.__T)
    .map((raw) => parseTopic(raw))
    .filter((topic): topic is Topic => topic !== undefined)

  const forum = isRecord(root.__F) ? root.__F : undefined
  const board = parseBoard(forum)
  // sub_forums 的 key 是 fid/stid 而不是下标，按数字排会把版块顺序打乱；
  // 服务端下发的顺序就是版块想要的展示顺序，原样保留
  const subForums = isRecord(forum?.sub_forums) ? Object.entries(forum.sub_forums) : []
  const subBoards = subForums
    .map(([key, raw]) => parseSubBoard(key, raw))
    .filter((item): item is Board => item !== undefined)

  const rowsPerPage = nonZero(int(root, '__T__ROWS_PAGE')) ?? DEFAULT_ROWS_PER_PAGE
  const totalRows = int(root, '__ROWS') ?? topics.length

  return {
    topics,
    ...(board === undefined ? {} : { board }),
    subBoards,
    totalRows,
    rowsPerPage,
    totalPages: Math.max(1, Math.ceil(totalRows / rowsPerPage)),
  }
}

/**
 * 把无限滚动攒下的几页拼成一条列表。
 *
 * 必须按 tid 去重：置顶主题与版块镜像行**每页都会再回来一次**
 * （实测 fid=-7 第 1、2 页重叠 20 条），不去重列表会反复出现同一行。
 */
export function mergeTopicPages(pages: readonly TopicList[]): Topic[] {
  const seen = new Set<number>()
  const merged: Topic[] = []
  for (const page of pages) {
    for (const topic of page.topics) {
      if (seen.has(topic.tid)) continue
      seen.add(topic.tid)
      merged.push(topic)
    }
  }
  return merged
}

/** 主题列表的排序（功能文档 §2.2）。默认按最后回复，spec §4 定的默认值。 */
export type TopicSort = 'lastPost' | 'postDate'

export interface FetchTopicListOptions {
  /** 版块 id：合集传 stid、普通版块传 fid，二选一（CONTEXT.md「合集」） */
  readonly boardId: number
  readonly kind: 'board' | 'collection'
  /** 从 1 起 */
  readonly page: number
  readonly sort?: TopicSort
  readonly signal?: AbortSignal
}

/**
 * 拉一页主题列表（`POST thread.php`，API 文档 §2）。
 *
 * `data` 为空（被封或版块不存在）时抛 `kind: 'parse'`，交给上层走反封锁链兜底；
 * 有 `__T` 但一条都解不出来不算错——版块本来就可能是空的。
 */
export async function fetchTopicList(
  fetchNga: NgaFetcher,
  options: FetchTopicListOptions,
): Promise<TopicList> {
  const { boardId, kind, page, sort, signal } = options

  const result = await fetchNga({
    path: 'thread.php',
    query: {
      ...(kind === 'collection' ? { stid: boardId } : { fid: boardId }),
      page,
      ...(sort === 'postDate' ? { order_by: 'postdatedesc' } : {}),
    },
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '主题列表响应里没有 data', via: result.via })
  }
  return parseTopicList(result.data)
}
