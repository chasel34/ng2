/**
 * 帖子详情的解析（API 文档 §3，`read.php`）。
 *
 * 一页响应里有四张互相引用的表：
 *
 * ```jsonc
 * { "data": {
 *     "__GLOBAL": { "_ATTACH_BASE_VIEW": "img.nga.cn/attachments" },  // 附件域名，每次都读
 *     "__U":  { "66313282": { …用户… }, "-1": { "username": "#anony_…" } },
 *     "__R":  { "0": { "lou": 0, "authorid": -1, "content": "…", "comment": { … } } },
 *     "__T":  { "tid": …, "subject": "…", "author": "…" },
 *     "__ROWS": 242, "__R__ROWS_PAGE": 20 } }
 * ```
 *
 * 三处非显然的地方，都有单测钉着：
 *
 * 1. **匿名楼层的 `authorid` 是 `-1`、`-2` 这种页内序号**，不是 uid。不同页的 `-1`
 *    是不同的人，所以用户 key 一律加请求级前缀（API 文档 §3 最后一段）。
 * 2. **贴条在 `__R` 里占一条幽灵行**：只有 `subject`/`comment_to_id`、没有 `content`，
 *    真身挂在被贴楼层的 `comment` 下。不滤掉就会多渲染一个空楼层。
 * 3. **`attachs` 经常是空串而不是对象**，`avatar` 可能是 JSON 串（API 文档 §3 用户字段）。
 */

import { resolveAuthorName } from '../local'
import { NgaError, isRecord, type NgaFetcher } from '../net'
import { normalizeAttachBase, THUMBNAIL_SUFFIX } from './attachments'
import { int, orderedValues, str } from './fields'
import type { Floor, FloorAttachment, FloorClient, FloorUser, TopicDetail } from './types'

/** `read.php` 固定每页 20 楼（API 文档 §3）。 */
const DEFAULT_ROWS_PER_PAGE = 20

/** 禁言 buff（`ForumConstants.BUFF_MUTE_IDS`）。 */
const MUTE_BUFF_IDS = ['105', '117']

/** `yz` 的这个取值表示账号被 nuke。别的负值（如 -5）是另外的状态。 */
const NUKED_YZ = -1

/** 威望显示值 = 服务端 `rvrc` ÷ 10。 */
const REPUTATION_SCALE = 10

/** 没标题的主题（NGA 允许）。与主题列表用同一个占位。 */
const UNTITLED = '无标题'

/** avatar 是 JSON 串时，抠出里面第一个 http 地址（字段里的 `\/` 要先还原）。 */
const AVATAR_URL_PATTERN = /https?:\/\/[^"',\s\\]+/

/**
 * 头像地址。服务端这个字段有三种形态：普通 URL、空串、以及一坨 JSON
 * （`js_escap_avatar`，多套头像并存时）。JSON 那种取第一个 http URL。
 */
export function parseAvatarUrl(raw: unknown): string | undefined {
  if (typeof raw !== 'string') return undefined
  const value = raw.replaceAll('\\/', '/').trim()
  if (value === '') return undefined
  if (/^https?:\/\//.test(value)) return value
  return AVATAR_URL_PATTERN.exec(value)?.[0]
}

/**
 * 发帖设备（`from_client`）。取值像 `"8 Android"` / `"7 iOS"` / `"31 /"`——
 * 编号会随客户端版本变，认名字比认编号稳。
 */
function parseClient(raw: unknown): FloorClient {
  const value = typeof raw === 'string' ? raw.toLowerCase() : ''
  if (value.includes('android')) return 'android'
  if (value.includes('ios') || value.includes('iphone')) return 'ios'
  return 'other'
}

function parseMuted(raw: unknown): boolean {
  if (!isRecord(raw)) return false
  return MUTE_BUFF_IDS.some((id) => raw[id] !== undefined)
}

/**
 * 用户表的 key：实名用 uid 字符串，**匿名加请求级前缀**。
 * 前缀形式 `<context>,-1` 与 MNGA 一致，方便对拍。
 */
function userKey(rawKey: string, context: string): string {
  return rawKey.startsWith('-') ? `${context},${rawKey}` : rawKey
}

function parseUser(rawKey: string, raw: unknown, context: string, groups: Map<string, string>):
  | FloorUser
  | undefined {
  if (!isRecord(raw)) return undefined

  const rawName = str(raw, 'username') ?? ''
  const displayName = resolveAuthorName(rawName)
  const anonymous = rawKey.startsWith('-')
  // 匿名用户的 uid 字段是 0，不是真身
  const uid = anonymous ? undefined : int(raw, 'uid')
  const avatarUrl = parseAvatarUrl(raw.avatar)
  const memberId = int(raw, 'memberid')
  const level = memberId === undefined ? undefined : groups.get(String(memberId))
  // 签名是 BBCode（字段名 signature/sign 都见过，API 文档 §3）；空串 = 没设置
  const signature = str(raw, 'signature') ?? str(raw, 'sign')
  return {
    key: userKey(rawKey, context),
    ...(uid === undefined ? {} : { uid }),
    name: displayName === '' ? '匿名' : displayName,
    rawName,
    anonymous,
    ...(avatarUrl === undefined ? {} : { avatarUrl }),
    ...(level === undefined ? {} : { level }),
    ...(signature === undefined ? {} : { signature }),
    reputation: (int(raw, 'rvrc') ?? int(raw, 'fame') ?? 0) / REPUTATION_SCALE,
    postCount: int(raw, 'postnum') ?? int(raw, 'posts') ?? 0,
    muted: parseMuted(raw.buffs),
    nuked: int(raw, 'yz') === NUKED_YZ,
  }
}

/** `__GROUPS`：memberid → 用户组名（设计稿里的「级别」）。 */
function parseGroups(raw: unknown): Map<string, string> {
  const groups = new Map<string, string>()
  if (!isRecord(raw)) return groups
  for (const [key, value] of Object.entries(raw)) {
    if (!isRecord(value)) continue
    const name = str(value, '0')
    if (name !== undefined) groups.set(key, name)
  }
  return groups
}

/** 一条附件（`attachs` 的成员）。`attachs` 是空串时上层根本不会调到这里。 */
function parseAttachment(raw: unknown, base: string): FloorAttachment | undefined {
  if (!isRecord(raw)) return undefined
  const attachUrl = str(raw, 'attachurl')
  if (attachUrl === undefined) return undefined

  const url = `${base}/${attachUrl.replace(/^\/+/, '')}`
  // thumb 在旧客户端里判的是 `=="1"`，实测服务端给的是缩略图尺寸（56/120）。
  // 判「有值且不是 0」才对得上现在的响应——字符串 "0" 也要算没有，
  // 否则会拼出一个不存在的 .thumb.jpg，宫格里就是一格加载失败。
  const thumb = int(raw, 'thumb')
  const hasThumbnail = thumb !== undefined && thumb !== 0
  const sizeKb = int(raw, 'size')
  const name = str(raw, 'name')

  return {
    url,
    ...(hasThumbnail ? { thumbnailUrl: `${url}${THUMBNAIL_SUFFIX}` } : {}),
    kind: str(raw, 'type') ?? 'file',
    ...(name === undefined ? {} : { name }),
    ...(sizeKb === undefined ? {} : { sizeKb }),
  }
}

interface FloorContext {
  readonly context: string
  readonly attachBase: string
  /** 楼主身份：实名比 uid、匿名比 `#anony_` 串（同一人在同一主题里的串是固定的） */
  readonly starter: { readonly uid?: number; readonly rawName?: string }
  readonly users: ReadonlyMap<string, FloorUser>
}

function isStarterFloor(user: FloorUser | undefined, starter: FloorContext['starter']): boolean {
  if (user === undefined) return false
  if (user.anonymous) {
    return starter.rawName !== undefined && user.rawName === starter.rawName
  }
  return user.uid !== undefined && user.uid === starter.uid
}

/**
 * 一个楼层。贴条与热门回复是同一个结构，所以共用这个函数（贴条不再递归解贴条）。
 *
 * 返回 undefined 的两种情况：不是对象，或者**没有 `content` 字段**——后者就是
 * 贴条在 `__R` 里留下的幽灵行，真身已经挂在被贴楼层下面了。
 */
function parseFloor(raw: unknown, ctx: FloorContext, depth = 0): Floor | undefined {
  if (!isRecord(raw)) return undefined
  if (typeof raw.content !== 'string') return undefined

  const authorId = int(raw, 'authorid') ?? 0
  const authorKey = userKey(String(authorId), ctx.context)
  const attachs = raw.attachs
  const attachments = isRecord(attachs)
    ? orderedValues(attachs)
        .map((item) => parseAttachment(item, ctx.attachBase))
        .filter((item): item is FloorAttachment => item !== undefined)
    : []
  const notes =
    depth === 0
      ? orderedValues(raw.comment)
          .map((item) => parseFloor(item, ctx, depth + 1))
          .filter((item): item is Floor => item !== undefined)
      : []
  const subject = str(raw, 'subject')
  const vote = str(raw, 'vote')

  return {
    pid: int(raw, 'pid') ?? 0,
    lou: int(raw, 'lou') ?? 0,
    authorId,
    authorKey,
    isStarter: isStarterFloor(ctx.users.get(authorKey), ctx.starter),
    content: raw.content,
    ...(subject === undefined ? {} : { subject }),
    postedAt: int(raw, 'postdatetimestamp') ?? 0,
    postedAtText: str(raw, 'postdate') ?? '',
    score: int(raw, 'score') ?? 0,
    // alterinfo 非空 = 被编辑过（API 文档 §3）；内容是编辑记录，本票不展开
    edited: str(raw, 'alterinfo') !== undefined,
    client: parseClient(raw.from_client),
    attachments,
    notes,
    ...(vote === undefined ? {} : { vote }),
  }
}

export interface ParseTopicDetailOptions {
  /**
   * 请求级 context，用来给匿名用户 id 加前缀。
   * 同一次请求内必须一致，不同请求之间必须不同——否则第 2 页的 `-1`
   * 会和第 1 页的 `-1` 串成同一个人。
   */
  readonly context: string
}

/**
 * 解一页帖子详情。传的是响应的 `data`。
 *
 * 和主题列表一样，整页解不出来也不抛：被封时这一页是用户唯一能看到的东西，
 * 上层拿 `floors.length === 0` 判断要不要走兜底。
 */
export function parseTopicDetail(data: unknown, options: ParseTopicDetailOptions): TopicDetail {
  const root = isRecord(data) ? data : {}
  const { context } = options

  const attachBase = normalizeAttachBase(
    isRecord(root.__GLOBAL) ? root.__GLOBAL._ATTACH_BASE_VIEW : undefined,
  )

  // `__U` 里除了用户，还混着 `__GROUPS` / `__MEDALS` / `__REPUTATIONS` 三张附表
  // （不是嵌在 data 顶层，实测就在 __U 内部）；带 `__` 前缀的 key 一律不是用户。
  const userTable = isRecord(root.__U) ? root.__U : {}
  const groups = parseGroups(userTable.__GROUPS ?? root.__GROUPS)
  const users = new Map<string, FloorUser>()
  for (const [key, raw] of Object.entries(userTable)) {
    if (key.startsWith('__')) continue
    const user = parseUser(key, raw, context, groups)
    if (user !== undefined) users.set(user.key, user)
  }

  const topic = isRecord(root.__T) ? root.__T : {}
  const starterName = str(topic, 'author')
  const starterUid = int(topic, 'authorid')
  const ctx: FloorContext = {
    context,
    attachBase,
    starter: {
      // 匿名主题的 __T.authorid 是页内序号（实测 -3），认不得人，只有 author 串可信
      ...(starterUid === undefined || starterUid < 0 ? {} : { uid: starterUid }),
      ...(starterName === undefined ? {} : { rawName: starterName }),
    },
    users,
  }

  const floors = orderedValues(root.__R)
    .map((raw) => parseFloor(raw, ctx))
    .filter((floor): floor is Floor => floor !== undefined)

  // 热门回复只挂在主楼上（API 文档 §3）
  const mainPost = orderedValues(root.__R).find((raw) => isRecord(raw) && int(raw, 'lou') === 0)
  const hotReplies = (isRecord(mainPost) ? orderedValues(mainPost.hotreply) : [])
    .map((raw) => parseFloor(raw, ctx))
    .filter((floor): floor is Floor => floor !== undefined)

  const rowsPerPage = int(root, '__R__ROWS_PAGE') || DEFAULT_ROWS_PER_PAGE
  const totalRows = int(root, '__ROWS') ?? floors.length
  const boardName = isRecord(root.__F) ? str(root.__F, 'name') : undefined

  return {
    tid: int(topic, 'tid') ?? 0,
    subject: str(topic, 'subject') ?? UNTITLED,
    ...(boardName === undefined ? {} : { boardName }),
    page: int(root, '__PAGE') ?? 1,
    totalRows,
    rowsPerPage,
    totalPages: Math.max(1, Math.ceil(totalRows / rowsPerPage)),
    attachBase,
    floors,
    hotReplies,
    users: Object.fromEntries(users),
  }
}

/**
 * 每次请求换一个匿名 context。
 *
 * 只要求「同一次请求内一致、不同请求之间不同」，不需要密码学随机——
 * 时间戳 + 自增计数在 core 里没有平台依赖，也不会因为同一毫秒发两个请求撞号。
 */
let anonymousContextSeq = 0
function nextAnonymousContext(): string {
  anonymousContextSeq += 1
  return `${Date.now().toString(36)}.${anonymousContextSeq.toString(36)}`
}

export interface FetchTopicDetailOptions {
  readonly tid: number
  /** 从 1 起 */
  readonly page: number
  /** fav 码（CONTEXT.md「fav 码」），访问隐藏/过期主题必带 */
  readonly favCode?: string
  /** 只看某人（API 文档 §3 的 `authorid`）：服务端只回这个 uid 的楼层，分页随之重排 */
  readonly authorId?: number
  readonly signal?: AbortSignal
}

/**
 * 拉一页帖子详情（`POST read.php`，API 文档 §3）。
 *
 * UA 走 `windowsPhone` 档：MNGA 对 read.php 强制用它，实测更不容易被封
 * （core/net 只提供机制，选哪档由这里决定，见 02 票遗留问题 6）。
 */
export async function fetchTopicDetail(
  fetchNga: NgaFetcher,
  options: FetchTopicDetailOptions,
): Promise<TopicDetail> {
  const { tid, page, favCode, authorId, signal } = options

  const result = await fetchNga({
    path: 'read.php',
    query: {
      tid,
      page,
      ...(favCode === undefined ? {} : { fav: favCode }),
      ...(authorId === undefined ? {} : { authorid: authorId }),
      // v2 是 Android v4 的新版结构，_ATTACH_BASE_VIEW 就是它带出来的
      v2: 1,
    },
    userAgent: 'windowsPhone',
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '帖子详情响应里没有 data', via: result.via })
  }
  return parseTopicDetail(result.data, { context: nextAnonymousContext() })
}
