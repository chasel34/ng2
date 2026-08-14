/**
 * 版块分类树的解析（API 文档 §1.1）。
 *
 * `app_api.php?__lib=home&__act=category` 的响应长这样（真实抓包，见 __fixtures__）：
 *
 * ```jsonc
 * {
 *   "data":  { "0": { "_id": "wow", "name": "魔兽世界",
 *                     "groups": { "0": { "name": "…", "id": 10004,
 *                                        "forums": { "0": { "fid": 7, "stid": 123, "name": "…", "info": "…" } } } } } },
 *   "other": { "forum_icon_list": { … }, "appcolumn_notis": { … }, "forum_recommend": { … } }
 * }
 * ```
 *
 * 三层全部是**字符串数字键当数组**（API 文档 §0.6），自动 JSON 映射失效，只能手工遍历；
 * 每一层的字段都可能缺、可能是别的类型，所以坏条目一律跳过而不是让整棵树炸掉。
 */

import { signedBoardId } from '../local'
import { NgaError, isRecord, type NgaFetcher } from '../net'
import { int, nonZero, orderedEntries, orderedValues, str } from './fields'
import type { Board, BoardCategory, BoardGroup, BoardTree, HomeAnnouncement } from './types'

/**
 * 版块图标地址的拼法，全部来自 `other.forum_icon_list`：
 * 前缀 + id + 后缀（普通版块用 `f_px_l`/`f_sx_l`，合集用 `s_px_l`/`s_sx_l`）。
 *
 * `f` / `s` 两个字符串是**登记过图标的 id 清单**，形如 `" 639,?487,-349066,?268,…"`
 * （id 与 `?` 打头的缓存版本号交替）。清单外的 id 请求图标必 404 ——
 * 2026-08-07 抽样 14 个版块逐个实测，命中与否和 HTTP 200/404 完全一致——
 * 所以不在清单里就不给地址，免得一屏 60 个格子打一片 404。
 */
interface IconTable {
  readonly board?: { readonly prefix: string; readonly suffix: string; readonly ids: ReadonlySet<number> }
  readonly collection?: {
    readonly prefix: string
    readonly suffix: string
    readonly ids: ReadonlySet<number>
  }
}

function parseIconIds(value: unknown): ReadonlySet<number> {
  const ids = new Set<number>()
  if (typeof value !== 'string') return ids
  for (const token of value.split(',')) {
    const trimmed = token.trim()
    if (trimmed === '' || trimmed.startsWith('?')) continue
    const id = Number(trimmed)
    if (Number.isFinite(id)) ids.add(Math.trunc(id))
  }
  return ids
}

function parseIconTable(other: unknown): IconTable {
  if (!isRecord(other)) return {}
  const list = other.forum_icon_list
  if (!isRecord(list)) return {}

  const family = (prefixKey: string, suffixKey: string, idsKey: string) => {
    const prefix = str(list, prefixKey)
    // 后缀字段偶尔是逗号分隔的多档（c_sx_l 就是），取第一档
    const suffix = str(list, suffixKey)?.split(',')[0]
    if (prefix === undefined || suffix === undefined) return undefined
    return { prefix, suffix, ids: parseIconIds(list[idsKey]) }
  }

  return {
    board: family('f_px_l', 'f_sx_l', 'f'),
    collection: family('s_px_l', 's_sx_l', 's'),
  }
}

function iconUrl(table: IconTable, board: { id: number; kind: Board['kind'] }): string | undefined {
  const family = board.kind === 'collection' ? table.collection : table.board
  if (!family || !family.ids.has(board.id)) return undefined
  return `${family.prefix}${board.id}${family.suffix}`
}

function parseBoard(raw: unknown, table: IconTable): Board | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined

  // 0 不是有效 id：普通版块常见下发 stid:0 表示「不是合集」，
  // 当成真 stid 会把整个版块错判成合集，thread.php 也会拿 stid=0 去查
  // fid 可以是负数（-7 网事杂谈、个人版面），过一道符号还原；stid 是主题 id，不适用
  const fid = nonZero(signedBoardId(int(raw, 'fid')))
  const stid = nonZero(int(raw, 'stid'))
  // stid 优先于 fid（CONTEXT.md「合集」）：合集与普通版块互斥，下游一律只认这一个 id
  const id = stid ?? fid
  if (id === undefined) return undefined

  const kind: Board['kind'] = stid === undefined ? 'board' : 'collection'
  const info = str(raw, 'info')
  const icon = iconUrl(table, { id, kind })

  return {
    id,
    kind,
    ...(fid === undefined ? {} : { fid }),
    ...(stid === undefined ? {} : { stid }),
    name,
    ...(info === undefined ? {} : { info }),
    ...(icon === undefined ? {} : { iconUrl: icon }),
  }
}

function parseGroup(raw: unknown, fallbackId: string, table: IconTable): BoardGroup | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined
  const boards = orderedValues(raw.forums)
    .map((forum) => parseBoard(forum, table))
    .filter((board): board is Board => board !== undefined)
  if (boards.length === 0) return undefined
  return { id: int(raw, 'id') === undefined ? fallbackId : String(int(raw, 'id')), name, boards }
}

function parseCategory(raw: unknown, fallbackId: string, table: IconTable): BoardCategory | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined
  const id = str(raw, '_id') ?? fallbackId
  const groups = orderedValues(raw.groups)
    .map((group, index) => parseGroup(group, `${id}-group-${index}`, table))
    .filter((group): group is BoardGroup => group !== undefined)
  if (groups.length === 0) return undefined
  return { id, name, groups }
}

function parseAnnouncements(other: unknown): HomeAnnouncement[] {
  if (!isRecord(other)) return []
  const block = other.appcolumn_notis
  if (!isRecord(block)) return []
  const version = int(block, 'version') ?? 0

  return orderedEntries(block.notis).flatMap(([key, raw]) => {
    if (!isRecord(raw)) return []
    const title = str(raw, 'title')
    if (title === undefined) return []
    const url = str(raw, 'url')
    const startAt = int(raw, 'start_at')
    const endAt = int(raw, 'end_at')
    return [
      {
        id: `${version}-${key}`,
        title,
        ...(url === undefined ? {} : { url }),
        ...(startAt === undefined ? {} : { startAt }),
        ...(endAt === undefined ? {} : { endAt }),
      },
    ]
  })
}

/**
 * 解析整棵分类树。传的是 `parseNgaJson` 返回的 `root`——图标清单与公告都挂在
 * 顶层 `other` 上，不在 `data` 里。
 *
 * 一个版块都没解析出来时抛 `kind: 'parse'`：对调用方来说这和「被封」是一回事，
 * 该继续用上一次的本地缓存，而不是把首页刷成空。
 */
export function parseBoardTree(root: unknown): BoardTree {
  if (!isRecord(root)) {
    throw new NgaError({ kind: 'parse', message: '分类树响应顶层不是对象' })
  }
  const table = parseIconTable(root.other)

  const categories: BoardCategory[] = []
  // 「推荐版块」是服务端单独下发的一档，排在所有分类前面（对应设计稿第一个 tab）
  const recommend = isRecord(root.other)
    ? parseCategory(root.other.forum_recommend, 'recommend', table)
    : undefined
  if (recommend) categories.push(recommend)

  for (const [index, raw] of orderedValues(root.data).entries()) {
    const category = parseCategory(raw, `category-${index}`, table)
    if (category) categories.push(category)
  }

  if (categories.length === 0) {
    throw new NgaError({ kind: 'parse', message: '分类树里一个版块都没解析出来' })
  }

  return { categories, announcements: parseAnnouncements(root.other) }
}

/**
 * 拉线上分类树（`POST app_api.php?__lib=home&__act=category`，API 文档 §1.1）。
 *
 * 不需要登录：游客也能拿到完整的树，所以首页在没有账号时照样铺得满。
 */
export async function fetchBoardTree(
  fetchNga: NgaFetcher,
  signal?: AbortSignal,
): Promise<BoardTree> {
  const result = await fetchNga({
    path: 'app_api.php',
    query: { __lib: 'home', __act: 'category' },
    // 这个接口的数据横跨顶层的 `data` 与 `other`（图标清单、公告、推荐版块都在 other 里），
    // 解析拿的是 `root` 而不是 `data`——所以它不该受「顶层必须有 data 壳」那条约束。
    // 实测响应确实带 `data` 键，声明 bare 只是让「我们读的是顶层」这件事写在明面上
    envelope: 'bare',
    ...(signal === undefined ? {} : { signal }),
  })
  return parseBoardTree(result.root)
}

/**
 * 挑一条当前该展示的公告：`startAt`/`endAt` 划出展示窗口，缺省表示不限。
 * `now` 是毫秒时间戳，服务端字段是秒。
 */
export function pickActiveAnnouncement(
  announcements: readonly HomeAnnouncement[],
  now: number,
): HomeAnnouncement | undefined {
  const seconds = Math.floor(now / 1000)
  return announcements.find(
    (item) =>
      (item.startAt === undefined || seconds >= item.startAt) &&
      (item.endAt === undefined || seconds <= item.endAt),
  )
}
