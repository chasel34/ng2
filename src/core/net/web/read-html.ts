/**
 * `read.php` 网页版 → 与 `__output=8` 同构的信封（Web 反解，ADR-0002 / API 文档 §0.8）。
 *
 * 不带格式参数请求 `read.php` 拿到的是给浏览器看的整页 HTML，但**数据并没有被渲染死**：
 * 网页版自己也是靠内联 JS 把数据交给前端渲染的，所以逐楼数据、用户表、分页、错误码
 * 都以结构化的形态躺在 `<script>` 里。这里把它们抠回来，拼成 core/api 的
 * `parseTopicDetail` 本来就吃的那个 `data` 形状——反解成功后下游一行都不用改。
 *
 * 四个数据源（名字即 API 文档 §0.8 列的那几个）：
 *
 * | 内联 JS | 给出什么 |
 * |---|---|
 * | `commonui.postArg.proc(...)` | 逐楼元数据（pid / 楼号 / 作者 / 时间 / 赞数 / 发帖设备），正文与标题以元素 id 间接给出 |
 * | `commonui.userInfo.setAll({...})` | 用户表，与 JSON 的 `__U` **同构**（连 `__GROUPS`/`__MEDALS` 附表和匿名槽位都一样） |
 * | `var __PAGE` / `postArg.setDefault(...)` | 分页与主题元数据 |
 * | `<!--msgcodestart-->` | 服务端错误（找不到主题、权限不足…） |
 *
 * 与 JSON 路线的对拍在 `core/api/topic-detail.web.test.ts`（同一主题同一页的两份样本，
 * 楼层身份/正文/时间/附件/用户表逐条相等）。已知差距只有三处：
 *
 * - **投票**（`vote`）拿不到：网页版把投票模块交给另一段 JS 渲染，`proc` 的实参里没有。
 * - **贴条与热门回复的发帖设备**（`from_client`）恒为 null：网页版只给楼层，不给嵌套的那些。
 * - **匿名楼主在第 2 页及以后认不出「楼主」标记**：`#anony_` 串只在用户表里出现，
 *   而主楼不在场时没有别的线索指向楼主（JSON 路线每页都带 `__T.author`）。
 */

import type { NgaEnvelope } from '../envelope'
import { NgaError, isFakeError } from '../errors'
import { sanitizeNgaJson } from '../sanitize'
import {
  elementIdOf,
  findCall,
  findCalls,
  innerHtmlOf,
  parseObjectLiterals,
  readIntVariable,
  readMarkedSection,
  readStringVariable,
  type JsArgument,
} from './html-scan'

/** `read.php` 固定每页 20 楼；`__PAGE` 与 `setDefault` 都拿不到时的兜底。 */
const DEFAULT_ROWS_PER_PAGE = 20

/**
 * `commonui.postArg.proc(...)` 的实参位置表。
 *
 * 前 8 个是 key + 7 个 DOM 元素，之后是数据。位置由三份真实抓包与同一时刻的
 * `__output=8` 响应逐字段对齐得出（fixture `read-web-*` 与 `core/api/__fixtures__/read-*`），
 * 没对上的位置一律不猜。
 */
const ARG = {
  /** 楼号（数字）或 `'_<pid>'`（贴条）/ `'__<pid>'`（热门回复） */
  key: 0,
  subjectElement: 2,
  contentElement: 3,
  infoElement: 6,
  pid: 10,
  /** 楼层类型位（JSON 的 `type`），匿名等状态在里面 */
  type: 11,
  /** 作者 id，字符串；匿名是 `'-1'` 这种页内序号 */
  authorId: 13,
  postedAt: 14,
  /** `'<score_2>,<score>,<recommend>'`——只有中间那位（赞数）与 JSON 对齐验证过 */
  scores: 15,
  contentLength: 16,
  fromClient: 19,
} as const

/** `data.__R` 里一条楼层记录。键名与 JSON 路线完全一致（下游按这些名字取）。 */
type PostRecord = Record<string, unknown>

function stringOf(argument: JsArgument | undefined): string | undefined {
  if (argument === undefined) return undefined
  if (argument.kind === 'string') return argument.value
  if (argument.kind === 'number') return String(argument.value)
  return undefined
}

function numberOf(argument: JsArgument | undefined): number | undefined {
  if (argument === undefined) return undefined
  if (argument.kind === 'number') return argument.value
  if (argument.kind === 'string' && /^-?\d+$/.test(argument.value)) return Number(argument.value)
  return undefined
}

/** 只留文本：日期躺在 `<span title='reply time'>…</span>` 里。 */
function stripTags(html: string): string {
  return html.replace(/<[^>]*>/g, '').trim()
}

/**
 * `__ATTACH_BASE_VIEW` 在网页版里**只有域名**（`img.nga.cn`），
 * 而 JSON 的 `__GLOBAL._ATTACH_BASE_VIEW` 带路径（`img.nga.cn/attachments`）。
 * 补齐这一段路径，下游 `normalizeAttachBase` 才拼得出图片地址。
 */
function attachBaseOf(html: string): string | undefined {
  const raw = readStringVariable(html, '__ATTACH_BASE_VIEW')
  if (raw === undefined || raw.trim() === '') return undefined
  const value = raw.trim().replace(/\/+$/, '')
  return value.includes('/') ? value : `${value}/attachments`
}

/**
 * 用户表。网页版这段 JS 就是一整块 JSON，且键名、附表（`__GROUPS`/`__MEDALS`/
 * `__REPUTATIONS`）、匿名槽位（`"-1"`）与 JSON 接口的 `__U` 逐字段相同——
 * 所以这一档最省事：洗一遍直接当 `__U` 交下去。
 *
 * 要洗是因为它和 JSON 接口一样不合法：`remark` 字段里带裸 TAB
 * （`sanitizeNgaJson` 第 7 步管的正是这个）。
 */
function parseUserTable(html: string): Record<string, unknown> {
  const table = findCall(html, 'commonui.userInfo.setAll(')?.args[0]
  if (table === undefined || table.kind !== 'expression') return {}
  try {
    const parsed: unknown = JSON.parse(sanitizeNgaJson(table.text))
    return parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
  } catch {
    // 用户表解不出来不该毁掉整页：楼层照样能渲染，只是作者名要退回 uid
    return {}
  }
}

/** `ubbcode.attach.load('postattach0','postcontent0',[{…}])` → 正文元素 id ↦ `attachs`。 */
function parseAttachments(html: string): ReadonlyMap<string, Record<string, unknown>> {
  const byContentId = new Map<string, Record<string, unknown>>()
  for (const call of findCalls(html, 'ubbcode.attach.load(')) {
    const contentId = stringOf(call.args[1])
    const list = call.args[2]
    if (contentId === undefined || list === undefined || list.kind !== 'expression') continue

    const attachs: Record<string, unknown> = {}
    parseObjectLiterals(list.text).forEach((item, index) => {
      if (item.url === undefined) return
      // 字段名对齐 JSON 的 `attachs`：网页版把 `attachurl` 叫 `url`，其余同名
      attachs[String(index)] = { ...item, attachurl: item.url }
    })
    if (Object.keys(attachs).length > 0) byContentId.set(contentId, attachs)
  }
  return byContentId
}

/** `commonui.loadAlertInfo('[E… ]','alertc3')` → 楼号 ↦ `alterinfo`（非空即「被编辑过」）。 */
function parseAlterInfo(html: string): ReadonlyMap<number, string> {
  const byLou = new Map<number, string>()
  for (const call of findCalls(html, 'commonui.loadAlertInfo(')) {
    const info = stringOf(call.args[0])
    const lou = Number(/^alertc(\d+)$/.exec(stringOf(call.args[1]) ?? '')?.[1])
    if (info === undefined || info.trim() === '' || !Number.isInteger(lou)) continue
    byLou.set(lou, info)
  }
  return byLou
}

/**
 * 贴条与热门回复都嵌在所属楼层的 HTML 里，靠外面那层 `<span>` 的 id 区分：
 * `comment_for_<pid>` 是贴条，`hightlight_for_<楼号>` 是热门回复（NGA 自己的拼写）。
 *
 * 按「离调用点最近的那个标记」判定，而不是认 `proc` 第一个实参的 `_` / `__` 前缀——
 * 后者只是拼 DOM id 的副产物，标记 span 才是网页版真正用来分区的东西。
 */
function nestedKindAt(html: string, at: number): 'note' | 'hotReply' {
  const note = html.lastIndexOf("id='comment_for_", at)
  const hot = html.lastIndexOf("id='hightlight_for_", at)
  return note > hot ? 'note' : 'hotReply'
}

interface ParsedPosts {
  /** `__R`：楼层流，键是页内序号（同 JSON） */
  readonly rows: Record<string, PostRecord>
  /** 楼号 0 那一楼的作者 key，认「楼主」用 */
  readonly starterAuthorId?: string
}

function buildPost(
  html: string,
  args: readonly JsArgument[],
  attachments: ReadonlyMap<string, Record<string, unknown>>,
): PostRecord | undefined {
  const contentId = elementIdOf(args[ARG.contentElement])
  if (contentId === undefined) return undefined
  const content = innerHtmlOf(html, contentId)
  if (content === undefined) return undefined

  const subjectId = elementIdOf(args[ARG.subjectElement])
  const subject = subjectId === undefined ? undefined : innerHtmlOf(html, subjectId)
  const infoId = elementIdOf(args[ARG.infoElement])
  const postedAtText = infoId === undefined ? undefined : stripTags(innerHtmlOf(html, infoId) ?? '')
  // `'0,43,0'` 的中间那位是赞数；两侧（score_2 / recommend）没有对齐验证过，不往下传
  const score = Number(stringOf(args[ARG.scores])?.split(',')[1] ?? '')
  const attachs = attachments.get(contentId)

  return {
    pid: numberOf(args[ARG.pid]) ?? 0,
    authorid: stringOf(args[ARG.authorId]) ?? '0',
    content,
    subject: subject ?? '',
    postdatetimestamp: numberOf(args[ARG.postedAt]) ?? 0,
    postdate: postedAtText ?? '',
    score: Number.isFinite(score) ? score : 0,
    type: numberOf(args[ARG.type]) ?? 0,
    content_length: numberOf(args[ARG.contentLength]) ?? 0,
    from_client: stringOf(args[ARG.fromClient]) ?? '',
    ...(attachs === undefined ? {} : { attachs }),
  }
}

function parsePosts(html: string): ParsedPosts {
  const attachments = parseAttachments(html)
  const alterInfo = parseAlterInfo(html)
  const rows: Record<string, PostRecord> = {}
  // 贴条/热门回复的 `proc` 排在所属楼层**之前**（它们嵌在那一楼的 HTML 里），
  // 所以先攒着，等到下一条楼层记录出现时挂上去
  let pendingNotes: PostRecord[] = []
  let pendingHotReplies: PostRecord[] = []
  let index = 0
  let starterAuthorId: string | undefined
  /** pid ↦ 楼号，给热门回复补它在本页的楼号用 */
  const louByPid = new Map<number, number>()

  for (const call of findCalls(html, 'commonui.postArg.proc(')) {
    const key = call.args[ARG.key]
    const post = buildPost(html, call.args, attachments)
    if (post === undefined) continue

    if (key?.kind !== 'number') {
      const bucket = nestedKindAt(html, call.at) === 'note' ? pendingNotes : pendingHotReplies
      bucket.push(post)
      continue
    }

    const lou = key.value
    const info = alterInfo.get(lou)
    rows[String(index)] = {
      ...post,
      lou,
      ...(info === undefined ? {} : { alterinfo: info }),
      ...(pendingNotes.length === 0 ? {} : { comment: { ...pendingNotes } }),
      ...(pendingHotReplies.length === 0 ? {} : { hotreply: { ...pendingHotReplies } }),
    }
    louByPid.set(post.pid as number, lou)
    if (lou === 0) starterAuthorId = post.authorid as string
    pendingNotes = []
    pendingHotReplies = []
    index += 1
  }

  // 热门回复的楼号网页版没直接给，但它就是本页某一楼——按 pid 认回来
  for (const row of Object.values(rows)) {
    const hot = row.hotreply
    if (hot === undefined || typeof hot !== 'object') continue
    for (const reply of Object.values(hot as Record<string, PostRecord>)) {
      const lou = louByPid.get(reply.pid as number)
      if (lou !== undefined) reply.lou = lou
    }
  }

  return { rows, ...(starterAuthorId === undefined ? {} : { starterAuthorId }) }
}

interface Pagination {
  readonly page: number
  readonly rowsPerPage: number
  readonly totalRows: number
}

/**
 * 分页。两个来源互相校准：
 *
 * - `var __PAGE = {0:'…',1:15,2:1,3:20}` —— 总页数 / 当前页 / 每页楼数，
 *   这是网页版页码条自己用的那份，**跟着「只看某人」这类过滤走**。
 * - `postArg.setDefault(…, type, replies, lastpost, 每页楼数)` —— 末四位里的 `replies`
 *   是主题回复总数（`__ROWS = replies + 1`，三份抓包都对得上），比页数精确。
 *
 * 两者一致就用精确值；不一致（过滤视图）以 `__PAGE` 的页数为准，
 * 总楼数退回「页数 × 每页」——宁可偏大，也不能让页码条少一页翻不过去。
 */
function parsePagination(html: string): Pagination {
  const pageVar = /var\s+__PAGE\s*=\s*\{([^}]*)\}/.exec(html)?.[1]
  const field = (key: string): number | undefined => {
    if (pageVar === undefined) return undefined
    const raw = new RegExp(`(?:^|,)\\s*${key}\\s*:\\s*(\\d+)`).exec(pageVar)?.[1]
    return raw === undefined ? undefined : Number(raw)
  }

  const rowsPerPage = field('3') ?? DEFAULT_ROWS_PER_PAGE
  const page = readIntVariable(html, '__CURRENT_PAGE') ?? field('2') ?? 1
  const totalPages = field('1')

  const replies = setDefaultReplies(html)
  if (replies !== undefined) {
    const rows = replies + 1
    if (totalPages === undefined || Math.max(1, Math.ceil(rows / rowsPerPage)) === totalPages) {
      return { page, rowsPerPage, totalRows: rows }
    }
  }
  return { page, rowsPerPage, totalRows: (totalPages ?? 1) * rowsPerPage }
}

/** `setDefault` 末四位是 `type, replies, lastpost, 每页楼数`，从尾巴数比从头数稳。 */
function setDefaultReplies(html: string): number | undefined {
  const call = findCalls(html, 'commonui.postArg.setDefault(')[0]
  return call === undefined ? undefined : numberOf(call.args.at(-3))
}

/** `setDefault` 第 4 位是楼主 id（匿名时是 `-3` 这类页内序号）。 */
function setDefaultStarterId(html: string): number | undefined {
  const call = findCalls(html, 'commonui.postArg.setDefault(')[0]
  return call === undefined ? undefined : numberOf(call.args[3])
}

/** `<!--msgcodestart-->2048<!--msgcodeend-->` + `<!--msginfostart-->找不到主题<!--msginfoend-->`。 */
function readMessage(html: string): { readonly code: string; readonly info: string } | undefined {
  const code = readMarkedSection(html, 'msgcode')?.trim()
  if (code === undefined) return undefined
  return { code, info: readMarkedSection(html, 'msginfo')?.trim() ?? '' }
}

/**
 * 反解一页 `read.php` 网页版 HTML。
 *
 * 契约与 `parseNgaJson` 一致：解析不出来抛 `kind: 'parse'`（可重试，链继续往下走），
 * 服务端语义错误抛 `kind: 'server'`（不重试），命中假错误白名单的当成功。
 */
export function parseReadPageHtml(html: string, via?: string): NgaEnvelope {
  const message = readMessage(html)
  if (message !== undefined) {
    // JSON 路线的 `error.0` 就是 `"2048:找不到主题"`，这里拼成同一句，
    // 好让错误页无论走哪条路线都显示同样的话
    const text = message.info === '' ? message.code : `${message.code}:${message.info}`
    if (!isFakeError(message.info)) {
      throw new NgaError({ kind: 'server', message: text, code: message.code, via })
    }
  }

  const { rows, starterAuthorId } = parsePosts(html)
  if (Object.keys(rows).length === 0) {
    throw new NgaError({ kind: 'parse', message: 'Web 反解没有找到任何楼层', via })
  }

  const users = parseUserTable(html)
  const pagination = parsePagination(html)
  const starterId = setDefaultStarterId(html)
  // 楼主名认「楼主」标记用。实名从用户表取；**匿名只有第 1 页认得出**——
  // `#anony_` 串只出现在用户表里，而第 2 页起主楼不在场，没有别的线索指向楼主。
  const starterKey =
    starterId !== undefined && starterId > 0 ? String(starterId) : (starterAuthorId ?? '')
  const starterName = (() => {
    const record = users[starterKey]
    if (record === null || typeof record !== 'object') return undefined
    const name = (record as Record<string, unknown>).username
    return typeof name === 'string' ? name : undefined
  })()

  const attachBase = attachBaseOf(html)
  const data = {
    ...(attachBase === undefined ? {} : { __GLOBAL: { _ATTACH_BASE_VIEW: attachBase } }),
    __U: users,
    __T: {
      tid: readIntVariable(html, '__CURRENT_TID') ?? 0,
      subject: innerHtmlOf(html, 'currentTopicName')?.trim() ?? '',
      ...(starterId === undefined ? {} : { authorid: starterId }),
      ...(starterName === undefined ? {} : { author: starterName }),
    },
    __F: { name: innerHtmlOf(html, 'currentForumName')?.trim() ?? '' },
    __R: rows,
    __PAGE: pagination.page,
    __ROWS: pagination.totalRows,
    __R__ROWS_PAGE: pagination.rowsPerPage,
  }

  return { root: { data }, data }
}
