/**
 * 版块收藏(CONTEXT.md「版块收藏」——云端收藏的版块列表,与主题收藏夹无关)。
 *
 * 接口族只有一个端点(API 文档 §1.3,2026-08-08 真实抓包,见 __fixtures__):
 *
 * ```
 * POST nuke.php?__lib=forum_favor2&__act=forum_favor
 * form: action=get                    → 列表,版块数组在 data["0"];空收藏时 data 是 {}
 * form: action=add|del, fid=<id>      → 增删,成功时 data["0"] 是文本「操作成功」
 * ```
 *
 * 实测要点:
 * - **合集也走 `fid` 参数**:把 stid 当 fid 传,服务端自己识别(传 31576766 后
 *   列表条目带的是 `stid`),不存在的 id 报「合集不存在」。
 * - 列表条目形如 `{id, fid?|stid?, name, info?}`,fid 与 stid 互斥;新收藏的排在最前。
 * - `del` 天然幂等(删未收藏的照样「操作成功」);`add` 重复收藏报错
 *   「你已经收藏了这个版面」,这里吞掉当成功,让上层的乐观切换不怕竞态。
 * - 需要登录:游客请求报「你必须先登录论坛」(server 错误),UI 层应先挡住入口。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { int, nonZero, orderedValues, str } from './fields'
import type { Board } from './types'

const FAVOR_QUERY = { __lib: 'forum_favor2', __act: 'forum_favor' } as const

function parseFavorBoard(raw: unknown): Board | undefined {
  if (!isRecord(raw)) return undefined
  const name = str(raw, 'name')
  if (name === undefined) return undefined

  // 与分类树同一套规则(board-tree.ts):0 不是有效 id,stid 优先于 fid
  const fid = nonZero(int(raw, 'fid'))
  const stid = nonZero(int(raw, 'stid'))
  const id = stid ?? fid ?? nonZero(int(raw, 'id'))
  if (id === undefined) return undefined

  const info = str(raw, 'info')
  return {
    id,
    kind: stid === undefined ? 'board' : 'collection',
    ...(fid === undefined ? {} : { fid }),
    ...(stid === undefined ? {} : { stid }),
    name,
    ...(info === undefined ? {} : { info }),
  }
}

/**
 * 解析 `action=get` 的 `data`。版块数组在 `data["0"]`;一个都没收藏时
 * `data` 是 `{}`(连 `"0"` 键都没有),返回空数组而不是报错。
 */
export function parseBoardFavorites(data: unknown): Board[] {
  if (!isRecord(data)) return []
  return orderedValues(data['0'])
    .map(parseFavorBoard)
    .filter((board): board is Board => board !== undefined)
}

/** 拉云端收藏列表,服务端顺序(新收藏在前)原样保留。 */
export async function fetchBoardFavorites(
  fetchNga: NgaFetcher,
  signal?: AbortSignal,
): Promise<Board[]> {
  const result = await fetchNga({
    path: 'nuke.php',
    query: FAVOR_QUERY,
    form: { action: 'get' },
    ...(signal === undefined ? {} : { signal }),
  })
  return parseBoardFavorites(result.data)
}

async function writeFavor(
  fetchNga: NgaFetcher,
  action: 'add' | 'del',
  boardId: number,
  signal?: AbortSignal,
): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: FAVOR_QUERY,
    // 合集也传 fid(见文件头注释);服务端语义错误由 envelope 抛 NgaError(kind: 'server')
    form: { action, fid: boardId },
    ...(signal === undefined ? {} : { signal }),
  })
}

/**
 * 收藏一个版块(合集传 stid,一样走 `fid` 参数)。
 * 「已经收藏」不算失败:乐观切换后重试/多端并发时,结果与意图一致。
 */
export async function addBoardFavorite(
  fetchNga: NgaFetcher,
  boardId: number,
  signal?: AbortSignal,
): Promise<void> {
  try {
    await writeFavor(fetchNga, 'add', boardId, signal)
  } catch (error) {
    if (error instanceof NgaError && error.kind === 'server' && error.message.includes('已经收藏')) {
      return
    }
    throw error
  }
}

/** 取消收藏。服务端本身幂等,删未收藏的也返回成功。 */
export async function removeBoardFavorite(
  fetchNga: NgaFetcher,
  boardId: number,
  signal?: AbortSignal,
): Promise<void> {
  await writeFavor(fetchNga, 'del', boardId, signal)
}

/**
 * 清空收藏:服务端没有批量接口,只能先拉列表再逐个删。
 * 串行而不是并发——收藏一般就十来个,不值得为它冒被风控的险(ADR-0002 的克制原则)。
 * 返回删掉的列表,给「撤销」重新收藏用。
 */
export async function clearBoardFavorites(
  fetchNga: NgaFetcher,
  signal?: AbortSignal,
): Promise<Board[]> {
  const boards = await fetchBoardFavorites(fetchNga, signal)
  for (const board of boards) {
    await removeBoardFavorite(fetchNga, board.id, signal)
  }
  return boards
}

/**
 * 「添加版面 ID」对话框的输入解析:接受十进制整数(fid 可以是负数,如网事杂谈 -7)。
 * 输入到底是 fid 还是 stid 由服务端定夺——add 时统一传 `fid` 参数,
 * 之后重拉列表,条目带 `stid` 就是合集(stid 优先,CONTEXT.md「合集」)。
 */
export function parseBoardIdInput(text: string): number | undefined {
  const trimmed = text.trim()
  if (!/^-?\d+$/.test(trimmed)) return undefined
  const id = Number(trimmed)
  return Number.isSafeInteger(id) && id !== 0 ? id : undefined
}
