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

import { NgaError, isRecord, type NgaEnvelope, type NgaFetcher, type NgaRequest } from '../net'
import {
  decodeTitleStyle,
  isAnonymousAuthor,
  parseTopicMisc,
  resolveAuthorName,
  signedBoardId,
} from '../local'
import { int, nonZero, orderedValues, str, text } from './fields'
import type {
  Board,
  SubBoard,
  Topic,
  TopicList,
  TopicParent,
  TopicReply,
  TopicShortcut,
} from './types'

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
  // fid 过一道符号还原：NGA 的版块 id 可以是负数。stid 是主题 id，不适用
  const fid = nonZero(signedBoardId(int(value, '0')))
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

/**
 * `searchpost=1` 时每条主题多出来的 `__P`（API 文档 §2）：那条回复本身。
 * 没有 `pid` 的不算——过期占位行的 `__P` 也带 pid，那种由 `denied` 单独标。
 */
function parseTopicReply(raw: unknown): TopicReply | undefined {
  if (!isRecord(raw)) return undefined
  const pid = nonZero(int(raw, 'pid'))
  if (pid === undefined) return undefined
  return {
    pid,
    content: typeof raw.content === 'string' ? raw.content : '',
    postedAt: int(raw, 'postdatetimestamp') ?? int(raw, 'postdate') ?? 0,
  }
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
  // `topic_misc` 那条路已经在 TLV 解码时还原过符号；`topic_misc_var` 是服务端预解析的
  // 同一份东西，它有没有丢符号我们说了不算，所以这一路也过一道
  const sfid =
    misc.sfid ?? (miscVar === undefined ? undefined : nonZero(signedBoardId(int(miscVar, '3'))))

  const type = int(raw, 'type') ?? 0
  const fid = nonZero(signedBoardId(int(raw, 'fid')))
  const rawAuthor = str(raw, 'author') ?? ''
  const favCode = tpcurl === undefined ? undefined : FAV_PATTERN.exec(tpcurl)?.[1]
  const parent = parseParent(raw.parent)
  const jumpUrl = str(raw, 'jumpurl')
  const authorId = nonZero(int(raw, 'authorid'))
  const lastPoster = str(raw, 'lastposter')
  const shortcut = parseShortcut(type, { tid, fid, stid, sfid })
  const reply = parseTopicReply(raw.__P)

  return {
    tid,
    ...(fid === undefined ? {} : { fid }),
    subject: text(raw, 'subject') ?? UNTITLED,
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
    ...(reply === undefined ? {} : { reply }),
    // `denied:"1"` 是服务端拒给内容的标记（帖子过期/无权限），此时 subject 就是拒绝理由
    denied: str(raw, 'denied') === '1',
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
 * 子版块（`__F.sub_forums`）：值是 `{0:id, 1:名字, 2:副标题, 3:filter_id, 4:订阅状态码}`，
 * **key 以 `t` 开头表示这是合集**（值里的 id 是 stid，见调研报告 §2）。
 *
 * 第 3、4 项是订阅/屏蔽（23 票）要用的：`filter_id` 是操作对象，
 * 有没有这一项还决定 `user_option` 的 `type`（进而决定 add/del 哪个是订阅）。
 * 判定与操作都在 `sub-board.ts`，这里只如实带出来。
 */
function parseSubBoard(key: string, raw: unknown): SubBoard | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, '1')
  const collection = key.startsWith('t')
  // 合集那一档的 id 是主题 id，不能套版块 id 的符号还原
  const id = nonZero(collection ? int(raw, '0') : signedBoardId(int(raw, '0')))
  if (name === undefined || id === undefined) return undefined

  const board = boardIdentity(name, collection ? { stid: id } : { fid: id })
  if (board === undefined) return undefined
  const info = str(raw, '2')
  const filterId = nonZero(int(raw, '3'))
  return {
    ...board,
    ...(info === undefined ? {} : { info }),
    filterId: filterId ?? id,
    filterType: filterId === undefined ? 0 : 1,
    attributes: int(raw, '4') ?? 0,
  }
}

/** 当前版块（`__F`）。 */
function parseBoard(raw: unknown): Board | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined
  const board = boardIdentity(name, {
    fid: nonZero(signedBoardId(int(raw, 'fid'))),
    stid: nonZero(int(raw, 'stid')),
  })
  // 版头（CONTEXT.md）：`topped_topic` 存版头帖 tid，没有时是 0 或空串
  const head = nonZero(int(raw, 'topped_topic'))
  return board === undefined ? undefined : { ...board, ...(head === undefined ? {} : { head }) }
}

/**
 * `thread.php` 的响应里，这些键任意一个在场就说明「服务端确实按主题列表回了话」。
 *
 * 空版块也会有 `__T:{}` 与 `__F`——**一条主题都没有 ≠ 没有这个结构**。
 * 一个都没有的响应根本不是这个接口的东西（被限流、被拦、或者轮换到了一个
 * 我们没验证过的格式档），2026-08-13 之前这种响应会一路变成「这个版块还没有主题」。
 */
const TOPIC_LIST_STRUCTURE_KEYS = ['__T', '__F', '__ROWS'] as const

/** 这份 `data` 是不是一页主题列表的形状。 */
export function hasTopicListStructure(data: unknown): boolean {
  return isRecord(data) && TOPIC_LIST_STRUCTURE_KEYS.some((key) => key in data)
}

/** 形状不对时的说明，两处（链内 `validate` 与链外兜底）共用同一句话。 */
const NOT_A_TOPIC_LIST = '响应里没有主题列表结构（多半是被限流或拦截了）'

/**
 * 反封锁链的一票否决（`NgaRequest.validate`）：所有走 `thread.php` 的调用都挂它。
 *
 * 有了它，「能洗成 JSON 但不是主题列表」的响应会被当成 `kind:'parse'` 继续轮换，
 * **坏组合也就进不了成功组合缓存**——否则一次瞬时失败就能把 `thread.php` 这条
 * 缓存记录钉死在坏组合上，版块/搜索/收藏夹/热帖一起空到进程重启为止。
 */
export function rejectNonTopicList(envelope: NgaEnvelope): string | undefined {
  // 假错误（「2048:没有符合条件的结果」= 翻到底了）是正常终止，不是坏组合
  if (envelope.fakeError !== undefined) return undefined
  if (!isRecord(envelope.data)) return '响应里没有 data'
  return hasTopicListStructure(envelope.data) ? undefined : NOT_A_TOPIC_LIST
}

/** 走 `thread.php` 的请求都带上这一份（形状校验 + 明确信封形状）。 */
export const TOPIC_LIST_REQUEST = {
  validate: rejectNonTopicList,
} as const satisfies Pick<NgaRequest, 'validate'>

/**
 * 服务端明确回了「2048:没有符合条件的结果」（假错误白名单）时的空列表。
 *
 * 和「我们根本没拿到列表」不是一回事，所以 `listStructure` 是 true：
 * 服务端把话说清楚了，只是内容为空。
 */
export function serverEmptyTopicList(): TopicList {
  return { ...parseTopicList({}), listStructure: true }
}

/**
 * 解一页主题列表。传的是响应的 `data`。
 *
 * 整页解不出来也不抛：主题列表是无限滚动的，某一页坏掉应该只是"这页没东西"，
 * 上层拿 `topics.length === 0` 判断要不要停止翻页；**到底是"没帖"还是"没拿到"
 * 看 `listStructure`**。
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
    .filter((item): item is SubBoard => item !== undefined)

  const rowsPerPage = nonZero(int(root, '__T__ROWS_PAGE')) ?? DEFAULT_ROWS_PER_PAGE
  // `__ROWS` 在「某人的回复」里是**空串**（服务端不算这个总数），`int` 会把它读成 0,
  // 直接用就变成「总共 0 条 / 共 1 页」。空/0 时退到本页条数 `__T__ROWS`。
  const totalRows =
    nonZero(int(root, '__ROWS')) ?? nonZero(int(root, '__T__ROWS')) ?? topics.length

  return {
    topics,
    ...(board === undefined ? {} : { board }),
    subBoards,
    totalRows,
    rowsPerPage,
    totalPages: Math.max(1, Math.ceil(totalRows / rowsPerPage)),
    listStructure: hasTopicListStructure(data),
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
  /**
   * 精华区（功能文档 §2.2）：`recommend=1`。Android 客户端还固定带
   * `order_by=postdatedesc&user=1`（API 文档 §2），精华区下 `sort` 不生效。
   */
  readonly recommend?: boolean
  readonly signal?: AbortSignal
}

/**
 * 拉一页主题列表（`POST thread.php`，API 文档 §2）。
 *
 * `data` 为空、或者压根不是主题列表的形状（被封、被限流、轮换到了没验证过的格式档）
 * 时抛 `kind: 'parse'`，交给上层走反封锁链兜底；
 * 有 `__T` 但一条都解不出来不算错——版块本来就可能是空的。
 */
export async function fetchTopicList(
  fetchNga: NgaFetcher,
  options: FetchTopicListOptions,
): Promise<TopicList> {
  const { boardId, kind, page, sort, recommend, signal } = options

  const result = await fetchNga({
    ...TOPIC_LIST_REQUEST,
    path: 'thread.php',
    query: {
      ...(kind === 'collection' ? { stid: boardId } : { fid: boardId }),
      page,
      ...(recommend === true
        ? { recommend: 1, order_by: 'postdatedesc', user: 1 }
        : sort === 'postDate'
          ? { order_by: 'postdatedesc' }
          : {}),
    },
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '主题列表响应里没有 data', via: result.via })
  }
  // 链内的 validate 已经拦过一道，这里再拦一次是为了让 fetchTopicList 的契约
  // 不依赖调用方传对了 validate（比如别处直接拿 direct 策略打这个接口）
  if (!hasTopicListStructure(result.data)) {
    throw new NgaError({ kind: 'parse', message: NOT_A_TOPIC_LIST, via: result.via })
  }
  return parseTopicList(result.data)
}
