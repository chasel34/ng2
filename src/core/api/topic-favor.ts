/**
 * 主题收藏夹(CONTEXT.md「收藏夹」——云端多夹,与版块收藏无关)。
 *
 * 走 `topic_favor_v2` 接口族(API 文档 §5.1),全部 JSON:
 *
 * - 收藏夹列表 `list_folder`:`data["0"].*` 是 `{id, name, length, default?}`,
 *   **有 `default` 键的那个是默认夹**(实测同时带 `type: 2`,以 `default` 为准)
 * - 加入 `add`(form `tid`)/ 移出 `del`(form **`tidarray`**,不是 `tid`!)——
 *   按夹删,取消一个夹不影响主题在其他夹里的归属(实测 2026-08-08)
 * - 新建 `new_folder`:新夹 id 在 `data["1"]` 或 `data["0"]`
 * - 重命名/设默认 `modify_folder`、删除 `del_folder`
 * - 某夹的主题列表 = `thread.php?favor=<夹id>`,响应形状与主题列表相同,复用其解析
 *
 * 写操作不在这里判「操作成功」文案:服务端出错时 envelope 已经抛 `kind: 'server'`,
 * 能走到返回就是成功。操作后的列表一律以重拉的服务端数据为准(ticket 11 验收项)。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { int, orderedValues, str } from './fields'
import { TOPIC_LIST_REQUEST, parseTopicList } from './topic-list'
import type { TopicList } from './types'

/** 一个云端收藏夹。 */
export interface FavoriteFolder {
  readonly id: number
  readonly name: string
  /** 夹内主题数(服务端字段名 `length`) */
  readonly count: number
  /** 收藏时不指定夹就落进默认夹;全站至多一个 */
  readonly isDefault: boolean
}

/** `topic_favor_v2` 的公共 query。写操作的业务参数按 NGA 惯例也放 query 之外的 form。 */
const LIB = 'topic_favor_v2'

/** 解收藏夹列表(传响应的 `data`)。坏条目跳过,整体不炸(core/api 纪律)。 */
export function parseFavoriteFolders(data: unknown): FavoriteFolder[] {
  const root = isRecord(data) ? data : {}
  return orderedValues(root['0'])
    .map((raw): FavoriteFolder | undefined => {
      if (!isRecord(raw)) return undefined
      const id = int(raw, 'id')
      const name = str(raw, 'name')
      if (id === undefined || name === undefined) return undefined
      return {
        id,
        name,
        count: int(raw, 'length') ?? 0,
        isDefault: 'default' in raw,
      }
    })
    .filter((folder): folder is FavoriteFolder => folder !== undefined)
}

/** 拉收藏夹列表。空 `data["0"]`(一个夹都没有)是合法状态,返回空数组。 */
export async function fetchFavoriteFolders(
  fetchNga: NgaFetcher,
  signal?: AbortSignal,
): Promise<FavoriteFolder[]> {
  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'list_folder', page: 1 },
    ...(signal === undefined ? {} : { signal }),
  })
  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '收藏夹列表响应里没有 data', via: result.via })
  }
  return parseFavoriteFolders(result.data)
}

export interface FetchFavoriteTopicsOptions {
  readonly folderId: number
  /** 从 1 起 */
  readonly page: number
  readonly signal?: AbortSignal
}

/**
 * 某收藏夹的主题列表(`thread.php?favor=<夹id>`,API 文档 §2)。
 * 响应形状与版块主题列表一致(`__F` 是空对象),直接复用 `parseTopicList`。
 */
export async function fetchFavoriteTopics(
  fetchNga: NgaFetcher,
  options: FetchFavoriteTopicsOptions,
): Promise<TopicList> {
  const { folderId, page, signal } = options
  const result = await fetchNga({
    ...TOPIC_LIST_REQUEST,
    path: 'thread.php',
    query: { favor: folderId, page },
    ...(signal === undefined ? {} : { signal }),
  })
  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '收藏主题列表响应里没有 data', via: result.via })
  }
  return parseTopicList(result.data)
}

/** 把主题加进一个收藏夹。 */
export async function addTopicFavorite(
  fetchNga: NgaFetcher,
  options: { readonly tid: number; readonly folderId: number },
): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'add' },
    form: { tid: options.tid, folder: options.folderId },
  })
}

/** 把主题从一个收藏夹移出。⚠️ 参数名是 `tidarray`(API 文档 §5.1)。 */
export async function removeTopicFavorite(
  fetchNga: NgaFetcher,
  options: { readonly tid: number; readonly folderId: number },
): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'del' },
    form: { tidarray: options.tid, folder: options.folderId },
  })
}

/** 设默认传 2、不动默认位传 0(API 文档 §5.1 的 `opt`)。 */
const OPT_DEFAULT = 2
const OPT_KEEP = 0

/**
 * 新建收藏夹,返回新夹 id;响应里挖不出 id 时返回 undefined——
 * 创建本身已成功(失败早抛了),调用方反正要重拉列表。
 */
export async function createFavoriteFolder(
  fetchNga: NgaFetcher,
  options: { readonly name: string; readonly asDefault?: boolean },
): Promise<number | undefined> {
  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'new_folder', raw: 3 },
    form: { name: options.name, opt: options.asDefault === true ? OPT_DEFAULT : OPT_KEEP },
  })
  // 新夹 id 在 data["1"] 或 data["0"](MNGA 调研报告 §E3);data["0"] 常是"操作成功"文案
  const data = isRecord(result.data) ? result.data : {}
  return int(data, '1') ?? int(data, '0')
}

/**
 * 重命名 / 设默认(同一个 `modify_folder`)。
 * `name` 必传:设默认时传夹的现名;`asDefault` 传 true 把这个夹设为默认。
 */
export async function modifyFavoriteFolder(
  fetchNga: NgaFetcher,
  options: { readonly folderId: number; readonly name: string; readonly asDefault?: boolean },
): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'modify_folder', raw: 3 },
    form: {
      folder: options.folderId,
      name: options.name,
      opt: options.asDefault === true ? OPT_DEFAULT : OPT_KEEP,
    },
  })
}

/** 删除收藏夹(夹里的收藏一并没了,UI 侧要把话说清楚)。 */
export async function deleteFavoriteFolder(
  fetchNga: NgaFetcher,
  options: { readonly folderId: number },
): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: LIB, __act: 'del_folder', raw: 3 },
    form: { folder: options.folderId },
  })
}
