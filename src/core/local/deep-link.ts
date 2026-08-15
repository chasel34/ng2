/**
 * 深链解析（24）：把外部点进来的链接、以及抽屉「由 URL 读取」里粘的那一行，
 * 归成 app 内的一个跳转目标。
 *
 * 认这几种写法：
 * ```
 * https://bbs.nga.cn/read.php?tid=123&page=2#pid456Anchor
 * ng2://read.php?tid=123          release 自定义 scheme（spec §2）
 * ng2-dev://read.php?tid=123      development 自定义 scheme
 * bbs.nga.cn/read.php?tid=123     手粘时常见的省略 scheme
 * /thread.php?fid=650             同上，只剩路径
 * ```
 *
 * 不借 `URL`：RN 上它是个残缺 polyfill，而 core 要在 Node（单测）与 Hermes（真机）
 * 上得出同一结果，所以从 scheme 到 query 全部手切。
 */

import { NGA_HOSTS } from '../net/constants'

/** release 自定义 scheme；development 另用 ng2-dev，避免并装时互相抢链接。 */
export const APP_SCHEME = 'ng2'
export const APP_DEV_SCHEME = 'ng2-dev'
const APP_SCHEMES: readonly string[] = [APP_SCHEME, APP_DEV_SCHEME]

/** 能接管的域名 = 官方域名清单（API 文档 §0.1），去掉协议头。 */
const NGA_HOSTNAMES: readonly string[] = NGA_HOSTS.map((origin) =>
  origin.replace(/^https?:\/\//, ''),
)

/** 深链只接管这两个端点（spec §2）。 */
const ENDPOINTS: Record<string, 'topic' | 'board'> = {
  'read.php': 'topic',
  'thread.php': 'board',
}

/** 主题详情：`read.php` 那一族参数（API 文档 §3）。 */
export interface NgaTopicLink {
  readonly kind: 'topic'
  readonly tid: number
  readonly page?: number
  /** 定位到某一楼；query 的 `pid` 或网页锚点 `#pid<pid>Anchor` */
  readonly pid?: number
  /** fav 码（CONTEXT.md）：隐藏/过期帖的钥匙，跳过去要一路带着 */
  readonly fav?: string
}

/** 主题列表：`thread.php` 的 fid / stid（API 文档 §2）。 */
export interface NgaBoardLink {
  readonly kind: 'board'
  /** 合集是 stid、普通版块是 fid，两者共用列表页的 `id` */
  readonly id: number
  readonly boardKind: 'board' | 'collection'
}

export type NgaLink = NgaTopicLink | NgaBoardLink

/** 解不出来的几种原因——UI 要据此说清楚是哪儿不对。 */
export type NgaLinkFailure =
  | 'empty'
  | 'unsupported-scheme'
  | 'foreign-host'
  | 'unsupported-path'
  | 'missing-id'

export type NgaLinkResult =
  | { readonly ok: true; readonly link: NgaLink }
  | { readonly ok: false; readonly reason: NgaLinkFailure }

/** 失败原因对应的人话，给「由 URL 读取」对话框就地显示。 */
export const NGA_LINK_FAILURE_MESSAGES: Record<NgaLinkFailure, string> = {
  empty: '先粘一条 NGA 链接进来',
  'unsupported-scheme': '这不像一条链接',
  'foreign-host': '只认 NGA 官方域名的链接',
  'unsupported-path': '只支持主题（read.php）与版块（thread.php）链接',
  'missing-id': '链接里没有 tid / fid，不知道要打开什么',
}

/**
 * 解析一条链接。
 *
 * 失败一律是 `{ ok: false, reason }`，不抛异常——调用方里有一个是 expo-router 的
 * 系统深链回调，那儿抛错会直接崩掉冷启动。
 */
export function parseNgaLink(input: string): NgaLinkResult {
  const trimmed = input.trim()
  if (trimmed === '') return fail('empty')

  const { scheme, host: rawHost, path, query, fragment } = splitLocation(trimmed)
  const web = scheme === 'http' || scheme === 'https'
  if (scheme !== undefined && !web && !APP_SCHEMES.includes(scheme)) {
    return fail('unsupported-scheme')
  }

  const host = normalizeHost(rawHost)
  // `ng2://read.php?tid=1` 与手粘的 `read.php?tid=1` 在 host 的位置上放的是端点而不是域名；
  // 除此之外，只要 host 位置有东西，就必须是官方域名——否则等于替别人的站接管链接
  const hostIsDomain = web || (host !== '' && ENDPOINTS[host] === undefined)
  if (hostIsDomain && !NGA_HOSTNAMES.includes(host)) return fail('foreign-host')

  const endpoint = lastSegment(hostIsDomain ? path : `${host}/${path}`)
  const target: 'topic' | 'board' | undefined = ENDPOINTS[endpoint]
  if (target === undefined) return fail('unsupported-path')

  const params = parseQuery(query)
  return target === 'topic' ? topicLink(params, fragment) : boardLink(params)
}

/**
 * 目标 → app 内路由。给 `+native-intent` 用（那儿只能返回字符串），
 * 「由 URL 读取」也走同一条，免得两处各写一份参数映射迟早走偏。
 */
export function ngaLinkPath(link: NgaLink): string {
  if (link.kind === 'board') return `/board/${link.id}?kind=${link.boardKind}`
  const query: string[] = []
  if (link.page !== undefined) query.push(`page=${link.page}`)
  if (link.pid !== undefined) query.push(`pid=${link.pid}`)
  if (link.fav !== undefined) query.push(`fav=${encodeURIComponent(link.fav)}`)
  return `/topic/${link.tid}${query.length === 0 ? '' : `?${query.join('&')}`}`
}

function topicLink(params: Map<string, string>, fragment: string): NgaLinkResult {
  const tid = positiveInt(params.get('tid'))
  // `read.php?pid=xxx` 这种只给 pid 的引用链接也存在，但换算成 tid 要再打一次接口，
  // 不在深链这一层做——直接说清楚缺什么
  if (tid === undefined) return fail('missing-id')
  const page = positiveInt(params.get('page'))
  // 网页版定位某楼靠的是锚点 `#pid<pid>Anchor`，query 里没写 pid 时按它算
  const pid = positiveInt(params.get('pid')) ?? positiveInt(/^pid(\d+)/i.exec(fragment)?.[1])
  const fav = favCode(params.get('fav'))
  return {
    ok: true,
    link: {
      kind: 'topic',
      tid,
      ...(page === undefined ? {} : { page }),
      ...(pid === undefined ? {} : { pid }),
      ...(fav === undefined ? {} : { fav }),
    },
  }
}

function boardLink(params: Map<string, string>): NgaLinkResult {
  // stid 与 fid 互斥且 stid 优先（CONTEXT.md「合集」）
  const stid = boardId(params.get('stid'))
  if (stid !== undefined) return { ok: true, link: { kind: 'board', id: stid, boardKind: 'collection' } }
  const fid = boardId(params.get('fid'))
  if (fid !== undefined) return { ok: true, link: { kind: 'board', id: fid, boardKind: 'board' } }
  return fail('missing-id')
}

interface Location {
  /** 小写；没写 scheme 时是 undefined */
  readonly scheme: string | undefined
  readonly host: string
  readonly path: string
  readonly query: string
  readonly fragment: string
}

function splitLocation(input: string): Location {
  const matched = /^([a-z][a-z0-9+.-]*):\/\/(.*)$/i.exec(input)
  const scheme = matched?.[1]?.toLowerCase()
  let rest = matched?.[2] ?? input

  const hash = rest.indexOf('#')
  const fragment = hash === -1 ? '' : rest.slice(hash + 1)
  if (hash !== -1) rest = rest.slice(0, hash)

  const mark = rest.indexOf('?')
  const query = mark === -1 ? '' : rest.slice(mark + 1)
  if (mark !== -1) rest = rest.slice(0, mark)

  if (rest.startsWith('/')) return { scheme, host: '', path: rest, query, fragment }
  const slash = rest.indexOf('/')
  if (slash === -1) return { scheme, host: rest, path: '', query, fragment }
  return { scheme, host: rest.slice(0, slash), path: rest.slice(slash), query, fragment }
}

function normalizeHost(raw: string): string {
  const withoutUserInfo = raw.slice(raw.lastIndexOf('@') + 1)
  return withoutUserInfo
    .replace(/:\d*$/, '')
    .toLowerCase()
    .replace(/\.$/, '')
    .replace(/^www\./, '')
}

function lastSegment(path: string): string {
  const segments = path.split('/').filter((segment) => segment !== '')
  return (segments[segments.length - 1] ?? '').toLowerCase()
}

function parseQuery(query: string): Map<string, string> {
  const params = new Map<string, string>()
  // 从网页正文复制来的链接常把 `&` 带成 HTML 实体
  for (const pair of query.replace(/&amp;/gi, '&').split('&')) {
    if (pair === '') continue
    const eq = pair.indexOf('=')
    const key = (eq === -1 ? pair : pair.slice(0, eq)).toLowerCase()
    // 同名参数以第一个为准
    if (params.has(key)) continue
    params.set(key, decodeValue(eq === -1 ? '' : pair.slice(eq + 1)))
  }
  return params
}

function decodeValue(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '))
  } catch {
    // 半截的 `%` 转义解不开，原样留着让下面的格式校验去否
    return value
  }
}

function positiveInt(value: string | undefined): number | undefined {
  if (value === undefined || !/^\d+$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined
}

/** fid 可以是负数（如 -7），stid 不会——两者共用这一套校验，只挡 0 与非整数。 */
function boardId(value: string | undefined): number | undefined {
  if (value === undefined || !/^-?\d+$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed !== 0 ? parsed : undefined
}

/** fav 码是十六进制串（API 文档 §2），别的形状一律当没带。 */
function favCode(value: string | undefined): string | undefined {
  return value !== undefined && /^[0-9a-f]+$/i.test(value) ? value : undefined
}

function fail(reason: NgaLinkFailure): NgaLinkResult {
  return { ok: false, reason }
}
