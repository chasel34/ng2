/**
 * 官方屏蔽词（API 文档 §11.5，云同步，与 NGA 网页版「控制面板 → 屏蔽」同一份数据）：
 *
 * ```
 * POST nuke.php?__lib=ucp&__act=get_block_word&uid=<uid>     # 读
 * POST nuke.php?__lib=ucp&__act=set_block_word&data=<D>      # 写
 * Referer: <host>/nuke.php?func=ucp&uid=<uid>                ← 必须带
 * ```
 *
 * 三个容易踩的点：
 *
 * 1. **读回来的不是结构化数据**，是 `data["0"]` 里的一段多行纯文本：
 *    第 1 行是格式版本（实测恒为 `1`），第 2 行是空格分隔的关键词，
 *    第 3 行是空格分隔的 `uid/用户名` 对。行数不够（从没设置过）要当空表，别报错。
 * 2. **写是整表覆盖**，没有增删接口。所以增删都得「先读回来 → 改内存里的表 → 整表写回」，
 *    调用方拿到的 `BlockWordList` 就是那张表。
 * 3. **`data` 走 GBK**（`gbk()` 标记，query 层负责按 GBK percent-encode）。
 *    用 UTF-8 写上去，网页版那边看到的就是乱码——「与网页版互通」全靠这一步。
 *
 * 分隔符是空格，所以**关键词与用户名里不能有空白**；`blockWordError` 在写之前拦掉。
 */

import { filterRuleId, type FilterRule } from '../local'
import { gbk, isRecord, type NgaFetcher } from '../net'
import { UCP_REFERER_PATH } from './user-profile'

/** 官方屏蔽表里的一个人。老数据可能只有名字没有 uid。 */
export interface BlockedUser {
  readonly uid?: number
  readonly name: string
}

/** 一整张官方屏蔽表（关键词 + 用户），读写都是它。 */
export interface BlockWordList {
  readonly words: readonly string[]
  readonly users: readonly BlockedUser[]
}

export const EMPTY_BLOCK_WORDS: BlockWordList = { words: [], users: [] }

/** 写入串的第一行，实测恒为 `1`（格式版本）。 */
const BLOCK_WORD_VERSION = '1'

/** 这个接口的 Referer 要带 uid（API 文档 §11.5）。 */
export function blockWordRefererPath(uid: number | string): string {
  return `${UCP_REFERER_PATH}&uid=${uid}`
}

function splitTokens(line: string | undefined): string[] {
  if (line === undefined) return []
  return line.split(/\s+/).filter((token) => token !== '')
}

/** `12345/张三` → `{ uid: 12345, name: '张三' }`；名字里可能有 `/`，只切第一个。 */
function parseBlockedUser(token: string): BlockedUser {
  const slash = token.indexOf('/')
  if (slash < 0) return { name: token }

  const uid = Number(token.slice(0, slash))
  const name = token.slice(slash + 1)
  if (!Number.isSafeInteger(uid) || uid <= 0) return { name: name === '' ? token : name }
  return { uid, name: name === '' ? String(uid) : name }
}

function formatBlockedUser(user: BlockedUser): string {
  return user.uid === undefined ? user.name : `${user.uid}/${user.name}`
}

/**
 * 解 `get_block_word` 的 `data`。从没设置过屏蔽词时服务端可能只回一行甚至空串，
 * 一律折成空表——「没有屏蔽词」不是错误。
 */
export function parseBlockWords(data: unknown): BlockWordList {
  const raw = typeof data === 'string' ? data : isRecord(data) ? data['0'] : undefined
  if (typeof raw !== 'string') return EMPTY_BLOCK_WORDS

  const lines = raw.split(/\r\n|[\r\n]/)
  return {
    words: splitTokens(lines[1]),
    users: splitTokens(lines[2]).map(parseBlockedUser),
  }
}

/** 拼 `set_block_word` 的 `data` 原文（GBK 编码由 query 层做）。 */
export function serializeBlockWords(list: BlockWordList): string {
  return [
    BLOCK_WORD_VERSION,
    list.words.join(' '),
    list.users.map(formatBlockedUser).join(' '),
  ].join('\r\n')
}

/**
 * 校验一条待写入的关键词/用户名，返回对话框就地显示的错误文案。
 * 空格是表里的分隔符，带空白的词写上去会被服务端拆成两条。
 */
export function blockWordError(text: string, label = '关键词'): string | undefined {
  const trimmed = text.trim()
  if (trimmed === '') return `请输入要屏蔽的${label}`
  if (/\s/.test(trimmed)) return `${label}中间不能有空格（官方屏蔽表用空格分隔）`
  return undefined
}

/**
 * 把云端这张表折成匹配器认的 `FilterRule`，好让列表与楼层只跑一次 `matchFilterRules`。
 *
 * **官方关键词一律按普通子串匹配**（`regex: false`）：这份表是空格分隔的一行文本，
 * 网页版那边到底有没有把某条当正则跑，我们无从判断；按子串走最多是少屏蔽几条，
 * 按正则走则可能把 `^` `[` 这类字符当语法误伤一大片。真机与网页版对拍后再调整。
 */
export function officialFilterRules(list: BlockWordList): FilterRule[] {
  const users = list.users.map(
    (user): FilterRule => ({
      id: filterRuleId('official', 'user', user.uid === undefined ? user.name : String(user.uid)),
      kind: 'user',
      origin: 'official',
      value: user.name,
      regex: false,
      ...(user.uid === undefined ? {} : { uid: user.uid }),
    }),
  )
  const words = list.words.map(
    (word): FilterRule => ({
      id: filterRuleId('official', 'keyword', word),
      kind: 'keyword',
      origin: 'official',
      value: word,
      regex: false,
    }),
  )
  return [...users, ...words]
}

export interface BlockWordOptions {
  /** 当前账号 uid，读取参数与 Referer 都要用 */
  readonly uid: number | string
  readonly signal?: AbortSignal
}

/** 拉云端屏蔽表。 */
export async function fetchBlockWords(
  fetchNga: NgaFetcher,
  options: BlockWordOptions,
): Promise<BlockWordList> {
  const { uid, signal } = options
  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'ucp', __act: 'get_block_word', uid },
    refererPath: blockWordRefererPath(uid),
    ...(signal === undefined ? {} : { signal }),
  })
  return parseBlockWords(result.data)
}

export interface SetBlockWordsOptions extends BlockWordOptions {
  /** 要写回去的整张表（不是增量） */
  readonly list: BlockWordList
}

/**
 * 整表写回。`data` 标了 `gbk()`——**这一步错了就与网页版对不上**：
 * query 层看到 GBK 参数会按 GBK percent-encode，并撤掉 `__inchst=UTF8`
 * （否则服务端按 UTF-8 解这串字节，存进去的是乱码）。
 */
export async function setBlockWords(
  fetchNga: NgaFetcher,
  options: SetBlockWordsOptions,
): Promise<void> {
  const { uid, list, signal } = options
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'ucp', __act: 'set_block_word', data: gbk(serializeBlockWords(list)) },
    refererPath: blockWordRefererPath(uid),
    ...(signal === undefined ? {} : { signal }),
  })
}
