/**
 * 金样本（golden master）导出器 —— 票 05a。
 *
 * 把 RN 版（TypeScript）的**纯函数**当 oracle：逐条喂语料、把输出规范化成 JSON，
 * 写进 `native/app/src/test/resources/goldens/<domain>/<case>.json`。
 * Kotlin 直译版在 `native` 工程里读同一批文件逐条对拍（票 05b）。
 *
 * 跑法（RN 仓库根目录）：
 *
 * ```bash
 * pnpm goldens:export
 * ```
 *
 * 为什么是 vitest 而不是 `node scripts/…`：本仓库没装 tsx / vite-node，
 * 而 `src/core/**` 全是无扩展名的 TS 相对导入，Node 原生的类型剥离解析不了；
 * vitest 是仓库里唯一现成的 TS 运行器。所以这个文件同时是「脚本」和「一条用例」——
 * 用例本身就是验收项「幂等（重跑零 diff）」。
 *
 * 格式约定与各 domain 的含义见
 * `native/app/src/test/resources/goldens/README.md`（那份是 05b 与 03/04/09/10 的唯一输入）。
 */

import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, it } from 'vitest'

import { parseBBCode, escapeForSubmit, unescapeNgaText, type BBCodeNode } from '../src/core/bbcode'
import {
  API_FIXTURES,
  fixtureContentType as apiFixtureContentType,
  readFixtureBytes as readApiFixtureBytes,
  type ApiFixtureName,
} from '../src/core/api/__fixtures__/index'
import {
  NET_FIXTURES,
  fixtureContentType as netFixtureContentType,
  readFixtureBytes as readNetFixtureBytes,
  type NetFixtureName,
} from '../src/core/net/__fixtures__/index'
import { decodeResponseBody, parseCharset } from '../src/core/net/encoding/decode-body'
import { parseNgaJson, type EnvelopeShape } from '../src/core/net/envelope'
import {
  NgaError,
  extractServerError,
  isAuthLevelServerError,
  isFakeError,
} from '../src/core/net/errors'
import { buildQueryString, gbk, hasGbkParam, type QueryParams } from '../src/core/net/query'
import { sanitizeNgaJson } from '../src/core/net/sanitize'
import { stripServerHtml } from '../src/core/net/server-text'
import { parseReadPageHtml } from '../src/core/net/web/read-html'

import { formatDiceTerms, resolveDice, type DiceSeed, type DiceTerm } from '../src/core/local/dice'
import {
  decodeAnonymousName,
  isAnonymousAuthor,
  resolveAuthorName,
} from '../src/core/local/anonymous'
import {
  decodeTitleStyle,
  parseTopicMisc,
  signedBoardId,
} from '../src/core/local/title-style'
import { ngaLinkPath, parseNgaLink, type NgaLink } from '../src/core/local/deep-link'
import {
  createFilterRule,
  filterMatchText,
  filterRuleId,
  matchFilterRules,
  normalizeRuleValue,
  removeFilterRule,
  topicCategories,
  upsertFilterRule,
  validateFilterRule,
  type FilterRule,
  type FilterRuleInput,
  type FilterSubject,
} from '../src/core/local/filters'
import {
  buildQuoteIndex,
  buildReplyChain,
  chainDepthOf,
  extractQuoteRefs,
  isReplyHeaderNode,
  quoteRefOf,
  replyHeaderRefOf,
  stripQuoteMarkup,
  type QuoteIndex,
  type QuoteIndexFloor,
} from '../src/core/local/reply-chain'
import { formatMoney, formatReputation, splitMoney, toReputation } from '../src/core/local/money'
import { aggregateHotTopics, type HotTopicCandidate } from '../src/core/local/hot-topics'
import { isVoteClosed, parseVote, voteSharePercent } from '../src/core/local/vote'

import {
  attachmentUrl,
  imageFileName,
  imageMimeType,
  normalizeAttachBase,
  rehostLegacyAttachment,
  stripThumbnailSuffix,
  thumbnailUrl,
} from '../src/core/api/attachments'
import { int, nonZero, orderedEntries, orderedValues, str, text } from '../src/core/api/fields'
import {
  hasTopicListStructure,
  mergeTopicPages,
  parseTopicList,
  rejectNonTopicList,
  serverEmptyTopicList,
} from '../src/core/api/topic-list'
import { parseAvatarUrl, parseTopicDetail } from '../src/core/api/topic-detail'
import { parseBoardTree, pickActiveAnnouncement } from '../src/core/api/board-tree'
import { parseBoardFavorites, parseBoardIdInput } from '../src/core/api/board-favor'
import { parseFavoriteFolders } from '../src/core/api/topic-favor'
import { notificationKind, parseNotificationFeed } from '../src/core/api/notifications'
import { parseUserProfile } from '../src/core/api/user-profile'
import { parseBoardSearch, parseUserSearchInput } from '../src/core/api/search'
import {
  blockWordError,
  parseBlockWords,
  serializeBlockWords,
  type BlockWordList,
} from '../src/core/api/block-word'
import {
  nextSubBoardState,
  subBoardOptionParam,
  subBoardState,
} from '../src/core/api/sub-board'
import type { Topic, TopicList } from '../src/core/api/types'

// ---------------------------------------------------------------------------
// 输出目录
// ---------------------------------------------------------------------------

const scriptDir = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(scriptDir, '..')
const GOLDENS_ROOT = join(repoRoot, 'native/app/src/test/resources/goldens')

// ---------------------------------------------------------------------------
// 规范化：稳定 JSON
// ---------------------------------------------------------------------------

/**
 * 自己写序列化而不是 `JSON.stringify(sortedObject)`：JS 引擎会把「整数样式的 key」
 * 提到对象最前面并按数值排序（NGA 的 data 全是这种 key），交给引擎排就不是字典序了。
 * 这里逐字符拼，键序恒为 `Array.prototype.sort()` 的字典序。
 */
function stringifyStable(value: unknown, indent: string): string {
  if (value === null) return 'null'
  const type = typeof value
  if (type === 'string') return JSON.stringify(value)
  if (type === 'boolean') return value ? 'true' : 'false'
  if (type === 'number') {
    if (!Number.isFinite(value as number)) {
      throw new Error(`金样本里不允许非有限数字：${String(value)}`)
    }
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    const inner = indent + '  '
    // 数组里的 undefined 只能落成 null（JSON 没有 undefined），与 JSON.stringify 同口径
    const items = value.map(
      (item) => `${inner}${stringifyStable(item === undefined ? null : item, inner)}`,
    )
    return `[\n${items.join(',\n')}\n${indent}]`
  }
  if (type === 'object') {
    const record = value as Record<string, unknown>
    const keys = Object.keys(record)
      .filter((key) => record[key] !== undefined)
      .sort()
    if (keys.length === 0) return '{}'
    const inner = indent + '  '
    const entries = keys.map(
      (key) => `${inner}${JSON.stringify(key)}: ${stringifyStable(record[key], inner)}`,
    )
    return `{\n${entries.join(',\n')}\n${indent}}`
  }
  throw new Error(`金样本里不允许的类型：${type}`)
}

// ---------------------------------------------------------------------------
// 一条金样本
// ---------------------------------------------------------------------------

interface Golden {
  /** kebab-case，同时是文件名（不含 .json） */
  readonly name: string
  /** 被测的 TS 函数名 */
  readonly fn: string
  readonly input: unknown
  /** 只在 input（或 input.bytes）是 base64 字节串时出现 */
  readonly inputEncoding?: 'base64'
  readonly expected: unknown
  /** 中文说明：这条锁的是什么 */
  readonly note?: string
}

const files = new Map<string, string>()
const counts = new Map<string, number>()

const KEBAB = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

function emit(domain: string, golden: Golden): void {
  if (!KEBAB.test(golden.name)) {
    throw new Error(`case 名必须是 kebab-case：${domain}/${golden.name}`)
  }
  const path = `${domain}/${golden.name}.json`
  if (files.has(path)) throw new Error(`重名 case：${path}`)
  files.set(path, `${stringifyStable(golden as unknown, '')}\n`)
  counts.set(domain, (counts.get(domain) ?? 0) + 1)
}

/** 跑一次被测函数：正常返回值原样，抛错折成 `{ throws: … }`。 */
function outcome(compute: () => unknown): unknown {
  try {
    const value = compute()
    return value === undefined ? null : value
  } catch (error) {
    return { throws: describeThrow(error) }
  }
}

function describeThrow(error: unknown): Record<string, unknown> {
  if (error instanceof NgaError) {
    return {
      kind: error.kind,
      message: error.message,
      retryable: error.retryable,
      ...(error.code === undefined ? {} : { code: error.code }),
      ...(error.status === undefined ? {} : { status: error.status }),
      ...(error.via === undefined ? {} : { via: error.via }),
    }
  }
  return { kind: 'error', message: error instanceof Error ? error.message : String(error) }
}

const base64 = (bytes: Uint8Array): string => Buffer.from(bytes).toString('base64')

// ===========================================================================
// domain: sanitize —— sanitizeNgaJson(raw) -> string
// ===========================================================================

function exportSanitize(): void {
  const cases: [name: string, raw: string, note?: string][] = [
    ['strip-js-var-prefix', 'window.script_muti_get_var_store={"data":1}', '§0.6 第 1 步'],
    [
      'strip-htmljs-page-shell',
      '<html><body><script>window.script_muti_get_var_store={"data":{"__MESSAGE":{"0":0}}};</script></body></html>',
      'lite=htmljs：整页 HTML 里只留 script 那段',
    ],
    [
      'truncate-error-tail',
      '{"data":{"0":"ok"}}/*error fill content 这里全是垃圾',
      '§0.6 第 2 步',
    ],
    ['strip-js-comment-marker', '{"data":/*$js$*/{"0":1}}', '§0.6 第 3 步'],
    [
      'fix-illegal-numbers',
      '{"content":+123,"subject":+45,"author":0678}',
      '§0.6 第 4 步：前导 + 与前导 0 转字符串',
    ],
    ['fix-illegal-numbers-mixed', '{"a":1,"content":0123}'],
    ['legal-numbers-untouched', '{"content":123,"subject":0,"author":-4}'],
    [
      'keep-alterinfo',
      '{"pid":1,"alterinfo":"[E1748252294 0 0]\t","lou":3}',
      '刻意不删 alterinfo（上游删是因为没有第 7 步）——删了就丢「已编辑」标记',
    ],
    ['quote-integer-keys', '{0:"a",1:{2:"b"}}', '§0.6 第 6 步'],
    ['quote-integer-keys-mixed', '{"x":1, 12:"y"}'],
    [
      'integer-keys-inside-string-untouched',
      '{"content":"看这段代码 {12:34} 还有 ,56: 这种"}',
      '裸正则会改坏正文 → 解析失败 → 被误判成被封',
    ],
    ['escape-control-chars-in-string', '{"content":"第一行\n第二行\ttab"}', '§0.6 第 7 步'],
    [
      'strip-assignment-wrapper',
      'window.script_muti_get_var_store=({"data":1});',
      '§0.6 之外：lite=js 会返回 =({…});',
    ],
    [
      'all-steps-combined',
      'window.script_muti_get_var_store=({"data":{0:{"pid":9,"alterinfo":"[E1 0 0]\t",' +
        '"content":+7,"subject":012,"note":"裸控制符"}}});/*error fill content xxx',
    ],
    ['empty-input', ''],
    ['plain-html-not-json', '<html>你被封了</html>', '被封时的响应：洗完仍不是 JSON'],
  ]

  for (const [name, raw, note] of cases) {
    emit('sanitize', {
      name,
      fn: 'sanitizeNgaJson',
      input: raw,
      expected: sanitizeNgaJson(raw),
      ...(note === undefined ? {} : { note }),
    })
  }

  // 真实抓包：洗完必须是合法 JSON
  const fixtures: [name: string, fixture: NetFixtureName][] = [
    ['capture-noti-empty', 'notiEmpty'],
    ['capture-thread-list', 'threadList'],
    ['capture-read-thread-jsvar', 'readThread'],
    ['capture-ucp-user', 'ucpUser'],
    ['capture-ucp-not-found', 'ucpNotFound'],
    ['capture-read-thread-not-found', 'readThreadNotFound'],
  ]
  for (const [name, fixture] of fixtures) {
    const raw = netFixtureText(fixture)
    emit('sanitize', {
      name,
      fn: 'sanitizeNgaJson',
      input: raw,
      expected: sanitizeNgaJson(raw),
      note: NET_FIXTURES[fixture].note,
    })
  }
}

// ===========================================================================
// domain: envelope —— parseNgaJson(text, via?, shape?)
// ===========================================================================

interface EnvelopeInput {
  readonly text: string
  readonly via?: string
  readonly shape?: EnvelopeShape
}

function envelopeExpected(input: EnvelopeInput): unknown {
  return outcome(() => {
    const envelope = parseNgaJson(input.text, input.via, input.shape)
    // `root` 故意不进期望值：它等于 JSON.parse(sanitize(text))，已被 sanitize domain 锁住，
    // 再存一份会让每条 golden 体积翻倍。
    return {
      data: envelope.data === undefined ? null : envelope.data,
      ...(envelope.time === undefined ? {} : { time: envelope.time }),
      ...(envelope.fakeError === undefined ? {} : { fakeError: envelope.fakeError }),
    }
  })
}

function exportEnvelope(): void {
  const cases: [name: string, input: EnvelopeInput, note?: string][] = [
    ['data-and-time', { text: '{"data":{"0":"ok"},"time":1786111705}' }],
    [
      'no-data-no-error-rejected',
      { text: '{"code":0,"msg":"","result":[]}', via: 'direct' },
      '默认 wrapped：陌生 JSON 不能当成「空数据」（2026-08-13 版块全空排查）',
    ],
    [
      'bare-shape-uses-root',
      { text: '{"code":0,"msg":"","result":[]}', shape: 'bare' },
      '调用方显式声明 bare 时顶层才当 data',
    ],
    ['bare-shape-still-unwraps-data', { text: '{"data":{"0":"ok"}}', shape: 'bare' }],
    ['empty-response', { text: '' }, '空响应算 parse 错误（可重试）'],
    ['not-json', { text: '<html>你被封了</html>' }, '解析失败 ≈ 被封 → 可重试'],
    ['top-level-not-object', { text: '[1,2,3]' }],
    [
      'server-error-not-retryable',
      { text: '{"error":{"code":403,"0":"找不到主题"}}', via: 'direct' },
      '服务端语义错误 kind=server 且 retryable=false',
    ],
    [
      'fake-error-is-success',
      { text: '{"error":{"0":"找不到用户"}}' },
      '假错误白名单：当成功返回，data 是 undefined',
    ],
    [
      'error-array-form',
      { text: '{"error":["访问速度过快"]}' },
      'PHP 数组形态的 error（2026-08-13 版块全空排查）',
    ],
    ['error-empty-array-is-not-error', { text: '{"data":{"0":1},"error":[]}' }],
  ]

  for (const [name, input, note] of cases) {
    emit('envelope', {
      name,
      fn: 'parseNgaJson',
      input,
      expected: envelopeExpected(input),
      ...(note === undefined ? {} : { note }),
    })
  }

  const fixtures: [name: string, fixture: NetFixtureName, note?: string][] = [
    ['capture-noti-empty', 'notiEmpty'],
    ['capture-ucp-user', 'ucpUser'],
    ['capture-thread-list', 'threadList'],
    ['capture-read-thread-jsvar', 'readThread'],
    ['capture-ucp-not-found', 'ucpNotFound'],
    ['capture-read-thread-not-found', 'readThreadNotFound'],
  ]
  for (const [name, fixture, note] of fixtures) {
    const input: EnvelopeInput = { text: netFixtureText(fixture), via: 'direct' }
    emit('envelope', {
      name,
      fn: 'parseNgaJson',
      input,
      expected: envelopeExpected(input),
      note: note ?? NET_FIXTURES[fixture].note,
    })
  }

  // 坏字节样本：整条链的 P0 回归（fid=414 在 app 里永远打不开）
  const broken: EnvelopeInput = { text: apiFixtureText('threadListBusyBroken'), via: 'direct' }
  emit('envelope', {
    name: 'capture-thread-list-414-broken-bytes',
    fn: 'parseNgaJson',
    input: broken,
    expected: envelopeExpected(broken),
    note:
      'thread.php fid=414 __output=8：服务端下发的字节本身就坏（U+FFFD 落在 \\" 转义上），' +
      'JSON.parse 必挂 → 必须抛 kind=parse（可重试），链才会轮到 __output=11 那一档',
  })
}

// ===========================================================================
// domain: errors —— 错误分类与服务端文案
// ===========================================================================

function exportErrors(): void {
  const fakeCases: [string, string][] = [
    ['fake-wanbi', '完毕'],
    ['fake-not-found-board', '没找到'],
    ['fake-no-result', '没有符合条件的结果'],
    ['fake-already-checked-in', '今天已经签到'],
    ['fake-user-not-found', '找不到用户'],
    ['fake-substring-post-done', '发贴完毕'],
    ['fake-substring-action-done', '操作完毕，正在跳转'],
    ['real-topic-not-found', '找不到主题'],
    ['real-not-logged-in', '未登录'],
    ['real-no-permission', '您没有权限进行此操作'],
  ]
  for (const [name, message] of fakeCases) {
    emit('errors', {
      name: `is-fake-error-${name}`,
      fn: 'isFakeError',
      input: message,
      expected: isFakeError(message),
    })
  }

  for (const [name, message] of [
    ['not-logged-in', '未登录'],
    ['guest-limit', '你没有登录或者登录信息已过期'],
    ['topic-not-found', '找不到主题'],
  ] as [string, string][]) {
    emit('errors', {
      name: `is-auth-level-${name}`,
      fn: 'isAuthLevelServerError',
      input: message,
      expected: isAuthLevelServerError(message),
      note: '命中 = 这一发没带上身份，值得换组合再试（不是语义失败）',
    })
  }

  const awaitingReview =
    "51:帖子正等待审核;<br/><a href='/nuke.php?func=account&amp;adminmode=1' " +
    "style='color:dimgray' target='_blank'>[查看所需的权限/条件]</a>"

  const stripCases: [string, string, string?][] = [
    ['awaiting-review', awaitingReview, 'M3 验收缺陷 2 的原始样本'],
    ['br-variants', '一<br>二<br/>三<br />四'],
    ['other-tags', '<b>权限不足</b>：<span style="color:red">需要 5 级</span>'],
    ['entities', '你没有权限&#39;访问&#39;&nbsp;这个版面&amp;合集'],
    ['angle-quotes-not-tags', '找不到主题《<第六感>》', '尖括号当引号的标题不能被吃掉'],
    ['collapse-blank-lines', '<div>上</div><br/><br/><br/>下<br/>  '],
    ['already-plain', '您没有浏览该版面的权限'],
    ['all-tags', '<br/>'],
  ]
  for (const [name, raw, note] of stripCases) {
    emit('errors', {
      name: `strip-server-html-${name}`,
      fn: 'stripServerHtml',
      input: raw,
      expected: stripServerHtml(raw),
      ...(note === undefined ? {} : { note }),
    })
  }

  const extractCases: [string, unknown, string?][] = [
    ['object-without-code', { error: { '0': '未登录' } }],
    ['object-with-code', { error: { code: 403, '0': '找不到主题' } }],
    ['object-multi-message', { error: { '0': 'a', '1': 'b', '2': '' } }, '多条用；连起来'],
    ['object-html-stripped', { error: { code: 51, '0': awaitingReview } }],
    ['string-form', { error: '<b>未登录</b>' }],
    ['string-all-tags-still-error', { error: '<br/>' }],
    ['array-form', { error: ['访问速度过快'] }],
    ['array-empty-is-null', { error: [] }],
    ['no-error', { data: {}, time: 1 }],
    ['not-a-record', 'not an object'],
    ['error-object-without-message', { error: { code: 7 } }],
  ]
  for (const [name, root, note] of extractCases) {
    emit('errors', {
      name: `extract-server-error-${name}`,
      fn: 'extractServerError',
      input: root,
      expected: extractServerError(root),
      ...(note === undefined ? {} : { note }),
    })
  }
}

// ===========================================================================
// domain: decode-body —— parseCharset / decodeResponseBody
// ===========================================================================

const utf8Bytes = (value: string): Uint8Array => new TextEncoder().encode(value)

function emitDecodeBody(
  name: string,
  bytes: Uint8Array,
  contentType: string | null,
  note?: string,
): void {
  emit('decode-body', {
    name,
    fn: 'decodeResponseBody',
    input: { bytes: base64(bytes), contentType },
    inputEncoding: 'base64',
    expected: decodeResponseBody(bytes, contentType),
    ...(note === undefined ? {} : { note }),
  })
}

function exportDecodeBody(): void {
  const charsetCases: [string, string | null][] = [
    ['declared-gbk', 'text/javascript; charset=GBK'],
    ['declared-utf8-quoted', 'text/html;charset="utf-8"'],
    ['declared-gb18030', 'text/html; charset=GB18030'],
    ['undeclared', 'text/html'],
    ['null', null],
  ]
  for (const [name, contentType] of charsetCases) {
    emit('decode-body', {
      name: `parse-charset-${name}`,
      fn: 'parseCharset',
      input: { contentType },
      expected: parseCharset(contentType),
    })
  }

  const yuanshenGbk = new Uint8Array([0xd4, 0xad, 0xc9, 0xf1])

  emitDecodeBody('declared-gbk', yuanshenGbk, 'text/javascript; charset=GBK', '声明了就信声明')
  emitDecodeBody('declared-utf8', utf8Bytes('原神'), 'application/json; charset=utf-8')
  emitDecodeBody(
    'undeclared-gbk-body',
    yuanshenGbk,
    'text/html',
    '无声明 → 先试 UTF-8，替换字符更少的那边胜出 → GB18030',
  )
  emitDecodeBody(
    'undeclared-utf8-body',
    utf8Bytes('原神'),
    'text/html',
    '无声明 → UTF-8 一个 U+FFFD 都没有，直接采纳',
  )
  emitDecodeBody('strip-bom', utf8Bytes('﻿{"a":1}'), 'application/json', 'BOM 要剥掉')
  emitDecodeBody('strip-bom-gbk', new Uint8Array([0xef, 0xbb, 0xbf, 0xd4, 0xad]), null)
  emitDecodeBody('empty-body', new Uint8Array([]), 'text/html')
  emitDecodeBody('ascii-only', utf8Bytes('{"a":1}'), null, '纯 ASCII：两边一样，走 UTF-8 分支')
  emitDecodeBody(
    'undeclared-both-lossy',
    new Uint8Array([0xff, 0xfe, 0xfd]),
    'text/html',
    '两边都出 U+FFFD：谁少用谁，平手时留 UTF-8',
  )
  emitDecodeBody(
    'unknown-charset-falls-back-to-vote',
    yuanshenGbk,
    'text/html; charset=iso-8859-1',
    '不认识的 charset 不硬用，退回投票',
  )

  // GB18030 **框法**的边界（票 03）。RN 版是手写状态机，Kotlin 侧的表问 JDK 要，
  // 但框法必须照抄 WHATWG：JDK 的 CharsetDecoder 在这几处与 WHATWG 不一样
  // （`A3 A0` 给 PUA、单独的 `0x80` 当非法字节、坏字节处一口气多吞几个字节）。
  // 而未声明 charset 时正是**按 U+FFFD 个数投票**选编码的，多吞一个字节就可能翻盘。
  const gbkFramingCases: [name: string, bytes: number[], note: string][] = [
    ['gbk-fullwidth-space', [0xa3, 0xa0], '`A3 A0` 是全角空格 U+3000，日常内容里到处都是'],
    ['gbk-standalone-euro', [0x41, 0x80, 0x42], '单独的 `0x80` 解成 €，不是非法字节'],
    [
      'gbk-lead-then-ascii',
      [0xd4, 0x20, 0x41],
      '尾字节非法 → 一个 U+FFFD，且 ASCII 尾字节退回流里当普通字符重解',
    ],
    ['gbk-lead-then-7f', [0xd4, 0x7f, 0x41], '`0x7F` 也算 ASCII 尾字节，同样退回流里'],
    ['gbk-half-four-byte', [0x81, 0x30, 0x41, 0x42], '半截四字节 → 退回 second 与尾字节重解'],
    ['gbk-truncated-four-byte', [0x81, 0x30, 0x81], '流末尾残留前导字节 → 一个 U+FFFD'],
    ['gbk-four-byte-astral', [0x90, 0x30, 0x81, 0x30], '四字节星平面段：U+10000'],
    ['gbk-four-byte-unmapped', [0xfe, 0x39, 0xfe, 0x39], '四字节 pointer 越界 → U+FFFD'],
    ['gbk-dangling-lead', [0xd4, 0xad, 0xd4], '尾部半个双字节序列'],
  ]
  for (const [name, bytes, note] of gbkFramingCases) {
    emitDecodeBody(name, new Uint8Array(bytes), 'text/javascript; charset=GBK', note)
  }

  const netFixtures: [string, NetFixtureName][] = [
    ['capture-thread-list-undeclared-gbk', 'threadList'],
    ['capture-read-thread-declared-gbk', 'readThread'],
    ['capture-read-web-not-found-gb18030', 'readWebNotFound'],
    ['capture-noti-empty', 'notiEmpty'],
  ]
  for (const [name, fixture] of netFixtures) {
    emitDecodeBody(
      name,
      readNetFixtureBytes(fixture),
      netFixtureContentType(fixture),
      NET_FIXTURES[fixture].note,
    )
  }

  emitDecodeBody(
    'capture-thread-list-414-broken-bytes',
    readApiFixtureBytes('threadListBusyBroken'),
    apiFixtureContentType('threadListBusyBroken'),
    'GBK 正文里混着 UTF-8 与两个谁都解不出的字节（0xac @21539、0x80 @27131）：' +
      '解码器只能吐 U+FFFD，这是「414 打不开」的根因，别把它当损坏文件删掉',
  )
  emitDecodeBody(
    'capture-thread-list-414-output11',
    readApiFixtureBytes('threadListBusyVerbose'),
    apiFixtureContentType('threadListBusyVerbose'),
    '同一页的 __output=11 替身，解得干干净净',
  )
}

// ===========================================================================
// domain: web —— read.php 网页版 HTML 反解（票 08，ADR-0002 / API 文档 §0.8）
// ===========================================================================

/**
 * `input` 是**解码后的整页 HTML 文本**（不是原始字节：解码由 `decode-body` domain 管），
 * `expected` 是信封的 `data`。
 *
 * **`root` 故意不进 `expected`**：`parseReadPageHtml` 的 `root` 就是 `{ data }`
 * ——同一份东西存两遍只让每条 golden 体积翻倍（理由同 `envelope` domain）。
 *
 * 坏样本（`not-found`）期望抛错：网页版的服务端语义错误夹在 `<!--msgcodestart-->`
 * 注释标记里，`kind:'server'`、不可重试。**这一份是故意留着的**（ADR-0002 第 9 条）。
 */
function exportWeb(): void {
  const fixtures: [name: string, fixture: NetFixtureName][] = [
    ['anonymous-hot-reply', 'readWebAnonymousHotReply'],
    ['comment', 'readWebComment'],
    ['attachments', 'readWebAttachments'],
    ['revalidate-45150945', 'readWebRevalidate'],
    ['not-found', 'readWebNotFound'],
  ]
  for (const [name, fixture] of fixtures) {
    const text = decodeResponseBody(readNetFixtureBytes(fixture), netFixtureContentType(fixture))
    emit('web', {
      name,
      fn: 'parseReadPageHtml',
      input: { text, via: 'web-fallback' },
      expected: outcome(() => parseReadPageHtml(text, 'web-fallback').data),
      note: NET_FIXTURES[fixture].note,
    })
  }

  // 一楼都没反解出来 = 大概率被封（`kind:'parse'`，可重试，链继续往下走）。
  // 合成向量而不是抓包：真被封时拿回来的是什么页面无法预先取样。
  const synthetic: [name: string, html: string, note: string][] = [
    [
      'no-floors',
      '<html><body>nothing here</body></html>',
      '不是一张 read.php 网页 → kind:parse（可重试），链接着往下走而不是当场收手',
    ],
    [
      'empty-body',
      '',
      '空响应同上：反解不出楼层就是 parse 错误，不是 server 错误',
    ],
  ]
  for (const [name, html, note] of synthetic) {
    emit('web', {
      name,
      fn: 'parseReadPageHtml',
      input: { text: html, via: 'web-fallback' },
      expected: outcome(() => parseReadPageHtml(html, 'web-fallback').data),
      note,
    })
  }
}

// ===========================================================================
// domain: entities
// ===========================================================================

function exportEntities(): void {
  const unescapeCases: [string, string, string?][] = [
    ['named-entities', 'a &lt;b&gt; &quot;c&quot;'],
    ['numeric-entities', '&#65;&#x42;&#20320;'],
    ['surrogate-pair', '&#55357;&#56836;', '按 UTF-16 码元还原，相邻代理对天然重组'],
    ['double-escaped-emoji', '&amp;#55357;&amp;#56836;', 'NGA 双重转义 → 解两遍'],
    [
      'zwj-family',
      'A&#55357;&#56834;B&#10084;&#65039;C' +
        '&#55357;&#56424;&#8205;&#55357;&#56425;&#8205;&#55357;&#56423;&#8205;&#55357;&#56422;',
    ],
    ['nbsp', 'a&nbsp;&nbsp;b', '&nbsp; 解成不间断空格，保住 NGA 排版'],
    ['unknown-entities-kept', '&zzz; &#; 100&50'],
    ['lone-surrogate', 'a&#55357;b', '落单代理码元换成替换字符'],
    ['empty', ''],
    ['plain-text', '普通中文 plain text'],
    ['out-of-range-codepoint', '&#1114112;'],
  ]
  for (const [name, raw, note] of unescapeCases) {
    emit('entities', {
      name: `unescape-${name}`,
      fn: 'unescapeNgaText',
      input: raw,
      expected: unescapeNgaText(raw),
      ...(note === undefined ? {} : { note }),
    })
  }

  const escapeCases: [string, string, string?][] = [
    ['emoji', '😄'],
    ['zwj-family', 'A😂B❤️C👨‍👩‍👧‍👦', '与 MNGA 样例逐字对上'],
    ['plain-kept', '[b]今天 & 明天[/b] <hi>', '中英文与 BBCode 不做 HTML 转义'],
    ['roundtrip-chinese', '纯中文签名'],
    ['roundtrip-fish', '摸鱼中 🐟'],
    ['roundtrip-empty', ''],
    ['roundtrip-quote', '[quote]引用[/quote]'],
    ['variation-selector', '❤️'],
    ['misc-symbols', '☀☂➿'],
  ]
  for (const [name, raw, note] of escapeCases) {
    emit('entities', {
      name: `escape-for-submit-${name}`,
      fn: 'escapeForSubmit',
      input: raw,
      expected: escapeForSubmit(raw),
      ...(note === undefined ? {} : { note }),
    })
  }
}

// ===========================================================================
// domain: bbcode —— parseBBCode(source) -> AST
// ===========================================================================

/** 渲染器覆盖清单（`src/ui/bbcode/coverage.test.ts`），29 种节点每种一段样例。 */
const COVERAGE_SAMPLES: [name: string, source: string][] = [
  ['text', '一段字'],
  ['linebreak', '上<br/>下'],
  ['bold', '[b]粗[/b]'],
  ['italic', '[i]斜[/i]'],
  ['underline', '[u]下划线[/u]'],
  ['strike', '[del]删除线[/del]'],
  ['color', '[color=red]红[/color]'],
  ['size', '[size=120%]大[/size]'],
  ['font', '[font=宋体]宋体[/font]'],
  ['code', '[code]const a = 1[/code]'],
  ['link', '[url=https://example.test]站外[/url]'],
  ['user-ref', '[uid=123]某人[/uid]'],
  ['topic-ref', '[tid]45150945[/tid]'],
  ['floor-ref', '[pid=1,2,3]Reply[/pid]'],
  ['mention', '[@某人]'],
  ['smiley', '[s:ac:blink]'],
  ['quote', '[quote]引用[/quote]'],
  ['image', '[img]./mon_202608/07/a.jpg[/img]'],
  ['divider', '======'],
  ['heading', '===标题==='],
  ['align', '[align=center]居中[/align]'],
  ['collapse', '[collapse=提要]藏起来的话[/collapse]'],
  ['list', '[list][*]甲[*]乙[/list]'],
  ['table', '[table][tr][td]甲[/td][td]乙[/td][/tr][/table]'],
  ['box', '[lessernuke]处罚说明[/lessernuke]'],
  ['dice', '[dice]1d100[/dice]'],
  ['flash', '[flash=video]./a.mp4[/flash]'],
  ['attach', '[attach]./a.zip[/attach]'],
  ['album', '[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]'],
]

/** 整段楼层：引用套 pid/uid、表情、相对路径图片、全程 `<br/>`、双重实体。 */
const FULL_FLOOR_CONTENT =
  '[quote][pid=123456,45150945,1]Reply[/pid] [b]Post by [uid=41417929]张三[/uid] (2026-08-07 12:00):[/b]<br/>' +
  '原话&lt;不要断章取义&gt;[/quote]' +
  '同意[s:ac:goodjob]<br/>' +
  '[img]./mon_202608/07/-abcdefg.jpg[/img]<br/>' +
  '详见 [url=https://bbs.nga.cn/read.php?tid=45150945]这帖[/url] 和 [@李四]<br/>' +
  '&amp;#55357;&amp;#56836;'

const BBCODE_CASES: [name: string, source: string, note?: string][] = [
  // —— 纯文本与换行
  ['plain-text', '你好世界'],
  ['empty-input', ''],
  ['double-entity-decode', '&amp;#55357;&amp;#56836; &lt;tag&gt;', '文本走两轮实体解码'],
  ['linebreak-variants', 'a<br/>b<br />c<br>d\ne\r\nf', '<br/> 三写法 + \\n + \\r\\n 统一成换行节点'],

  // —— 文字样式
  ['style-b', '[b]文字[/b]'],
  ['style-i', '[i]文字[/i]'],
  ['style-u', '[u]文字[/u]'],
  ['style-del', '[del]文字[/del]'],
  ['tag-name-case-insensitive', '[B]粗[/B]'],
  ['color-value-kept', '[color=crimson]红[/color]'],
  ['size-percent-kept', '[size=120%]大[/size]'],
  ['font-name-kept', '[font=宋体]字[/font]'],
  ['style-nesting', '[b]粗[i]又斜[/i][/b]'],

  // —— 引用与代码
  ['quote-inner-structure', '[quote][b]Post by 张三[/b]内容[/quote]'],
  ['code-no-inner-tags', '[code]if (a &lt; b) [b]x[/b][/code]', '[code] 内只做实体解码'],
  ['code-br-to-newline', '[code]a<br/>b[/code]'],

  // —— 折叠
  ['collapse-no-title', '[collapse]内容[/collapse]'],
  ['collapse-with-title', '[collapse=剧透]内容[/collapse]'],

  // —— 列表
  ['list-items', '[list][*]一[*]二[/list]'],
  ['list-ordered', '[list=1][*]一[/list]'],
  ['list-drops-whitespace', '[list]\n[*]一\n[*]二\n[/list]'],

  // —— 表格
  ['table-basic', '[table][tr][td]a[/td][td]b[/td][/tr][/table]', 'colspan/rowspan 默认 1'],
  ['table-cell-attrs', '[table][tr][td colspan=2 rowspan=3 width=100]a[/td][/tr][/table]'],
  ['table-drops-whitespace', '[table]\n[tr]\n[td]a[/td]\n[/tr]\n[/table]'],

  // —— 对齐 / 标题 / 分割线
  ['align-center', '[align=center]中[/align]'],
  ['align-right', '[align=right]右[/align]'],
  ['align-default', '[align]默认[/align]'],
  ['align-l', '[l]左[/l]'],
  ['align-r', '[r]右[/r]'],
  ['heading-tag', '[h]小标题[/h]'],
  ['heading-line', '===开场===<br/>正文', '行首的 ===x=== 是标题'],
  ['heading-line-with-tags', '===第[b]一[/b]章==='],
  ['divider-line', '上<br/>======<br/>下'],
  ['equals-mid-line-is-text', 'a===b=== c======'],

  // —— 链接与引用
  ['url-content-as-href', '[url]https://bbs.nga.cn/read.php?tid=1[/url]'],
  ['url-content-entity-only', '[url]https://x.com/?a=1&amp;b=[b][/url]'],
  ['url-with-label', '[url=https://x.com]看[b]这[/b][/url]'],
  ['uid-from-content', '[uid]41417929[/uid]'],
  ['uid-from-attr', '[uid=41417929]张三[/uid]'],
  ['tid-from-content', '[tid]45150945[/tid]'],
  ['tid-from-attr', '[tid=45150945]标题[/tid]'],
  ['pid-with-args', '[pid=123,456,1]Reply[/pid]', '第一个参数是 pid，其余留在 args'],
  ['pid-from-content', '[pid]123[/pid]'],

  // —— @提及
  ['mention-self-closing', '回复 [@小明] 你好'],
  ['mention-entity', '[@a&amp;b]'],
  ['mention-pair-form', '[@]小红[/@]'],
  ['mention-empty-passthrough', '[@]'],

  // —— 图片
  ['img-absolute', '[img]https://img.nga.cn/attachments/a.jpg[/img]'],
  ['img-relative-dot', '[img]./mon_202608/07/abc.jpg[/img]'],
  ['img-relative-bare', '[img]./a.jpg[/img]', 'AST 里不许出现任何写死的域名'],
  ['img-trim-whitespace', '[img]<br/> ./a.jpg <br/>[/img]'],
  ['noimg-relative', '[noimg]./07/x.jpg[/noimg]'],
  ['noimg-bare-filename', '[noimg]12345_abc.jpg[/noimg]', '[noimg] 的实际形态就是裸文件名'],
  ['img-protocol-relative', '[img]//img.nga.cn/a.jpg[/img]'],

  // —— 附件 / 相册 / flash
  ['attach-relative', '[attach]./mon_202608/07/f.zip[/attach]'],
  ['album-value', '[album]12345[/album]'],
  ['flash-video', '[flash=video]https://v.cn/a.mp4[/flash]'],
  ['flash-audio', '[flash=audio]https://v.cn/a.mp3[/flash]'],
  ['flash-bare', '[flash]https://v.cn/a.swf[/flash]'],
  ['flash-relative', '[flash=video]./mon_202608/07/a.mp4[/flash]'],

  // —— 表情
  ['smiley-category', '哈[s:ac:笑]哈'],
  ['smiley-numeric', '[s:14]'],
  ['smiley-underscore', '[s:pst:凯露_哭]'],
  ['smiley-broken-passthrough', '[s:]'],
  ['smiley-unresolved-ac', '[s:ac:根本不存在]'],
  ['smiley-unresolved-category', '[s:未知分类:名字]'],
  ['smiley-unresolved-numeric', '[s:99999]'],
  ['smiley-known', '[s:ac:goodjob]'],

  // —— 骰子
  ['dice-expression', '[dice]1d100[/dice]'],
  ['dice-attr-form', '[dice 2d6]', '没有闭标签，表达式藏在属性位'],

  // —— 特殊容器
  ['box-hip', '[hip]内容[/hip]'],
  ['box-item', '[item]内容[/item]'],
  ['box-lessernuke-inner-tags', '[lessernuke][b]警告[/b][/lessernuke]'],
  ['lessernuke-plain', '[lessernuke]内容[/lessernuke]'],
  ['lessernuke-1', '[lessernuke1]内容[/lessernuke1]'],
  ['lessernuke-2', '[lessernuke2]内容[/lessernuke2]'],
  ['lessernuke-3', '[lessernuke3]内容[/lessernuke3]'],

  // —— stripbr
  ['stripbr-basic', '前[stripbr]a<br/>b[/stripbr]后'],
  ['stripbr-only-own-level', '[stripbr]a<br/>[b]c<br/>d[/b][/stripbr]'],

  // —— 容错
  ['unknown-tag-passthrough', '前[randomblock]中[/randomblock]后'],
  ['unknown-tag-inner-known', '[style x]看[b]这[/b][/style]'],
  ['orphan-close-tag', '文字[/b]尾'],
  ['unclosed-tag-degrades', '[b]没关'],
  ['crossed-nesting', '[quote][b]交叉[/quote]'],
  ['close-across-levels', '[b][i][u]深[/b]'],
  ['unclosed-code-does-not-swallow', '[quote][code]abc[/quote]正文[code]x[/code]'],
  ['unclosed-img-does-not-swallow', '[b][img]./a.jpg[/b]'],
  ['brackets-are-literal', '[这是中文] [1] []'],
  ['randomblock-not-supported', '[randomblock]抽奖[/randomblock]', '不支持的标签降级纯文本'],

  // —— 遍历用例（walk.test.ts 的语料）
  ['walk-container', '[b]粗[/b]'],
  ['walk-list', '[list][*]甲[*]乙[/list]'],
  ['walk-table-two-rows', '[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]'],
  ['walk-leaf-text', '就是一段字'],
  [
    'walk-all-containers',
    '开头[b]粗[/b][quote]引用[/quote][collapse=提要]折叠[/collapse]' +
      '[list][*]甲[/list][table][tr][td]格子[/td][/tr][/table][align=center]居中[/align]',
  ],

  // —— 整段楼层
  ['full-floor', FULL_FLOOR_CONTENT, 'read.php 的 content 字段形态，整段对拍'],
]

function exportBBCode(): void {
  for (const [name, source] of COVERAGE_SAMPLES) {
    emit('bbcode', {
      name: `coverage-${name}`,
      fn: 'parseBBCode',
      input: source,
      expected: parseBBCode(source),
      note: '渲染器覆盖清单（29 节点）里的样例',
    })
  }

  const joined = COVERAGE_SAMPLES.map(([, source]) => source).join('<br/>')
  emit('bbcode', {
    name: 'coverage-all-joined',
    fn: 'parseBBCode',
    input: joined,
    expected: parseBBCode(joined),
    note: '29 种样例拼成一段长正文：解析出的类型不许超出清单',
  })

  for (const [name, source, note] of BBCODE_CASES) {
    emit('bbcode', {
      name,
      fn: 'parseBBCode',
      input: source,
      expected: parseBBCode(source),
      ...(note === undefined ? {} : { note }),
    })
  }

  // 超深嵌套：Kotlin 直译最容易在这条上爆栈（深度上限 64，超出部分退化成文本）
  const depth = 5000
  const deep = `${'[b]'.repeat(depth)}底${'[/b]'.repeat(depth)}`
  emit('bbcode', {
    name: 'deep-nesting-5000',
    fn: 'parseBBCode',
    input: deep,
    expected: parseBBCode(deep),
    note: 'MAX_NESTING_DEPTH=64：5000 层开标签不许撑爆调用栈，超出部分退化为文本',
  })
}

// ===========================================================================
// domain: dice
// ===========================================================================

interface DiceInput extends DiceSeed {
  readonly text: string
}

function exportDice(): void {
  const defaultSeed: DiceSeed = { authorId: 41417929, tid: 45150945, pid: 800000000 }

  const cases: [name: string, text: string, seed: DiceSeed, note?: string][] = [
    [
      'real-post-d100',
      '[dice]d100[/dice]',
      { authorId: 65352962, tid: 46868034, pid: 869683556 },
      '站上真帖对拍：楼主在下一楼写「74的现实偏离度」',
    ],
    [
      'real-post-four-d6-shared-stream',
      '空手道<br/>[dice]d6[/dice]<br/>脑神经<br/>[dice]d6[/dice]<br/>' +
        '本领<br/>[dice]d6[/dice]<br/>术(最终结果-3)<br/>[dice]d6[/dice]',
      { authorId: 60423359, tid: 46162468, pid: 857425480 },
      '同一楼四颗 d6 共用一条数列：楼主报「空手道3 脑神经6 本领3 术2」',
    ],
    [
      'real-post-d13',
      '[dice]d13[/dice]',
      { authorId: 60423359, tid: 46162468, pid: 857425573 },
      '下一楼的「竟然是火遁」对应第 1 项',
    ],
    [
      'real-post-constant-plus-dice',
      '有几个大陆？<br/>[dice]1+1d7[/dice]<br/>几个大洋？<br/>[dice]1+1d7[/dice]',
      { authorId: 65352962, tid: 46868034, pid: 869684145 },
      '楼主在下一楼写「3块大陆感觉有点少」',
    ],
    [
      'quoted-dice-different-seed',
      '[dice]d6[/dice]',
      { authorId: 66807492, tid: 46162468, pid: 857425600 },
      '官方帮助：引用他人的投骰代码会得到不同结果（种子含本楼 pid）',
    ],
    ['expression-2d6-plus-3', '[dice]2d6+3[/dice]', defaultSeed],
    ['expression-constant-first', '[dice]20+1d80[/dice]', defaultSeed],
    ['expression-omitted-count', '[dice]d20[/dice]', defaultSeed],
    ['expression-attr-form', '[dice d20]', defaultSeed, '[dice XdY] 走同一条路'],
    ['expression-pure-constant', '[dice]5[/dice]', defaultSeed, '纯数字不投骰'],
    ['limit-too-many-dice', '[dice]11d6[/dice]', defaultSeed, '>10 颗：网页版显示 OUT OF LIMIT'],
    ['limit-too-many-faces', '[dice]2d100001[/dice]', defaultSeed],
    ['limit-faces-exactly-100000', '[dice]1d100000[/dice]', defaultSeed, '等于 100000 仍放行'],
    ['shared-stream-three', '[dice]d6[/dice][dice]d6[/dice][dice]d6[/dice]', defaultSeed],
    [
      'shared-stream-nested',
      '[quote][dice]d6[/dice][/quote][b][dice]d6[/dice][/b][dice]d6[/dice]',
      defaultSeed,
      '嵌在引用/加粗/表格里的骰子也在同一条数列上，按文档顺序',
    ],
    ['same-expression-twice', '[dice]d100[/dice][dice]d100[/dice]', defaultSeed],
    [
      'collapse-only',
      '[collapse=提要][dice]d100[/dice][/collapse]',
      defaultSeed,
      '折叠块换一条数列（外层一颗都没投时才轮到 seedOffset）',
    ],
    [
      'collapse-after-outer-roll',
      '[dice]d100[/dice][collapse][dice]d100[/dice][/collapse]',
      defaultSeed,
      '外层投过：折叠块接着外层推进后的种子走',
    ],
    [
      'collapse-old-topic-no-offset',
      '[collapse=提要][dice]d100[/dice][/collapse]',
      { authorId: 1, tid: 10246184, pid: 200188932 },
      'seedOffset 只对新帖生效（tid>10246184 或 pid>200188932）',
    ],
    ['format-terms-source', '[dice]20+2d6[/dice]', defaultSeed],
  ]

  for (const [name, text, seed, note] of cases) {
    const input: DiceInput = { text, ...seed }
    const outcomes = [...resolveDice(parseBBCode(text), seed).values()]
    emit('dice', {
      name,
      fn: 'resolveDice',
      input,
      expected: outcomes,
      ...(note === undefined ? {} : { note }),
    })
  }

  const formatCases: [string, DiceTerm[]][] = [
    [
      'rolls-and-constant',
      [
        { kind: 'constant', value: 20 },
        { kind: 'roll', faces: 6, value: 4 },
        { kind: 'roll', faces: 6, value: 6 },
      ],
    ],
    ['single-roll', [{ kind: 'roll', faces: 100, value: 74 }]],
    ['empty', []],
  ]
  for (const [name, terms] of formatCases) {
    emit('dice', {
      name: `format-terms-${name}`,
      fn: 'formatDiceTerms',
      input: { terms },
      expected: formatDiceTerms(terms),
      note: '展开式显示串，与网页版同格式',
    })
  }
}

// ===========================================================================
// domain: anonymous
// ===========================================================================

function exportAnonymous(): void {
  const authors: [string, string, string?][] = [
    ['hex-0123', '#anony_0123456789abcdef0123456789abcdef'],
    ['hex-zeros', '#anony_00000000000000000000000000000000', '两张表的第一个字'],
    ['hex-random-1', '#anony_a3f01c7b92d4e6580a1b2c3d4e5f6071'],
    ['hex-random-2', '#anony_1e2d3c4b5a69788796a5b4c3d2e1f0aa'],
    [
      'hex-all-f',
      '#anony_ffffffffffffffffffffffffffffffff',
      '百家姓表只有 255 字，下标 0xff 官方就是取空 —— 照抄这个短名，勿修',
    ],
    [
      'hex-real-capture',
      '#anony_d43225f5a338ca2efea68a14773537e6',
      'thread.php?fid=-7 真实响应里的匿名作者，与设计稿从真机转录的「卯邱潘」对得上',
    ],
    ['not-anonymous-plain', '春曰影'],
    ['not-anonymous-short-hex', '#anony_0123'],
    ['not-anonymous-uppercase-hex', '#anony_0123456789ABCDEF0123456789ABCDEF', '官方正则只认小写'],
    ['not-anonymous-legacy-marker', '#ANONYMOUS#'],
  ]

  for (const [name, author, note] of authors) {
    const decoded = decodeAnonymousName(author)
    emit('anonymous', {
      name: `decode-${name}`,
      fn: 'decodeAnonymousName',
      input: author,
      expected: decoded === undefined ? null : decoded,
      ...(note === undefined ? {} : { note }),
    })
    emit('anonymous', {
      name: `resolve-author-${name}`,
      fn: 'resolveAuthorName',
      input: author,
      expected: resolveAuthorName(author),
    })
    emit('anonymous', {
      name: `is-anonymous-${name}`,
      fn: 'isAnonymousAuthor',
      input: author,
      expected: isAnonymousAuthor(author),
    })
  }
}

// ===========================================================================
// domain: title-style
// ===========================================================================

function exportTitleStyle(): void {
  const miscCases: [string, unknown, string?][] = [
    ['sfid-and-mask', 'AwAAA0MBAAAAIA', '真实抓包：fid=650 的版块镜像行，sfid=835 + 掩码 32'],
    ['stid-and-mask', 'AgH1lHMBAAAACA'],
    ['stid-only', 'AgH1lHM'],
    ['empty-string', ''],
    ['undefined', undefined],
    ['number', 42],
    ['invalid-base64', '!!!!'],
    ['tilde-suffix', 'AwAAA0MBAAAAIA~', '官方对 ~ / ~1 结尾直接返回空'],
    ['tilde-one-suffix', 'AwAAA0MBAAAAIA~1'],
    ['negative-sfid', 'A/962mEBAAAAIA', '高位为 1 的 sfid 是负数版块 id，不是四十亿'],
    ['stid-not-signed', 'Av962mE', 'stid 是主题 id，不适用符号修正'],
    ['mask-unsigned', 'Af8AACE', '掩码是位字段，仍按无符号读'],
  ]
  for (const [name, raw, note] of miscCases) {
    emit('title-style', {
      name: `parse-topic-misc-${name}`,
      fn: 'parseTopicMisc',
      input: { raw: raw === undefined ? null : raw },
      expected: parseTopicMisc(raw),
      ...(note === undefined ? {} : { note }),
    })
  }

  const signedCases: [string, number | undefined][] = [
    ['u32-negative-board', 4286241377],
    ['u32-minus-seven', 4294967289],
    ['normal-positive', 835],
    ['already-negative', -7],
    ['zero', 0],
    ['int32-max', 0x7fffffff],
    ['exactly-2-pow-32', 2 ** 32],
    ['large-topic-id', 47082733],
    ['undefined', undefined],
  ]
  for (const [name, value] of signedCases) {
    const result = signedBoardId(value)
    emit('title-style', {
      name: `signed-board-id-${name}`,
      fn: 'signedBoardId',
      input: { value: value === undefined ? null : value },
      expected: result === undefined ? null : result,
    })
  }

  const styleCases: [string, { titlefont?: unknown; topicMisc?: unknown }, string?][] = [
    ['topic-misc-red-bold', { topicMisc: 'AQAAACE' }, '真实抓包：置顶主题掩码 0x21'],
    ['topic-misc-unknown-high-bits', { topicMisc: 'AQQAACE' }, '真实抓包：活动帖 0x04000021'],
    ['titlefont-number', { titlefont: 2 }],
    ['titlefont-string', { titlefont: '196' }, '196 = 4(绿) + 64(斜) + 128(下划线)'],
    ['color-priority-all', { titlefont: 1 | 2 | 4 | 8 | 16 }, '红>蓝>绿>橙>银 只取第一个'],
    ['color-priority-blue', { titlefont: 2 | 4 | 8 | 16 }],
    ['color-priority-green', { titlefont: 4 | 8 | 16 }],
    ['color-priority-orange', { titlefont: 8 | 16 }],
    ['color-priority-silver', { titlefont: 16 }],
    ['topic-misc-wins', { topicMisc: 'AQAAACE', titlefont: 2 }, '两个来源都在时以 topic_misc 为准'],
    ['topic-misc-without-mask-falls-back', { topicMisc: 'AgH1lHM', titlefont: 2 }],
    ['empty-source', {}],
    ['empty-strings', { titlefont: '', topicMisc: '' }],
    ['titlefont-zero', { titlefont: 0 }],
  ]
  for (const [name, source, note] of styleCases) {
    emit('title-style', {
      name: `decode-title-style-${name}`,
      fn: 'decodeTitleStyle',
      input: source,
      expected: decodeTitleStyle(source),
      ...(note === undefined ? {} : { note }),
    })
  }
}

// ===========================================================================
// domain: attachments
// ===========================================================================

function exportAttachments(): void {
  const base = 'https://img.nga.cn/attachments'

  const normalizeCases: [string, unknown][] = [
    ['bare-host', 'img.nga.cn/attachments'],
    ['with-scheme-trailing-slash', 'https://img.nga.cn/attachments/'],
    ['http-upgraded', 'http://img.nga.cn/attachments'],
    ['undefined', undefined],
    ['empty-string', ''],
    ['number', 42],
  ]
  for (const [name, raw] of normalizeCases) {
    emit('attachments', {
      name: `normalize-attach-base-${name}`,
      fn: 'normalizeAttachBase',
      input: { raw: raw === undefined ? null : raw },
      expected: normalizeAttachBase(raw),
    })
  }

  const stripCases: [string, string][] = [
    ['thumb', 'mon_202607/21/a.jpg.thumb.jpg'],
    ['thumb-s', 'mon_202607/21/a.jpg.thumb_s.jpg'],
    ['thumb-ss', 'mon_202607/21/a.jpg.thumb_ss.jpg'],
    ['medium', 'mon_202607/21/a.jpg.medium.jpg'],
    ['no-suffix', 'mon_202607/21/a.jpg'],
    ['filename-called-thumb', 'mon_202607/21/thumb.jpg'],
  ]
  for (const [name, src] of stripCases) {
    emit('attachments', {
      name: `strip-thumbnail-suffix-${name}`,
      fn: 'stripThumbnailSuffix',
      input: src,
      expected: stripThumbnailSuffix(src),
    })
  }

  const rehostCases: [string, string, string, string?][] = [
    [
      'legacy-178-domain',
      'https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png',
      base,
      '178 域名已停（TLS 握手失败），同一路径挂当前基址仍是 200',
    ],
    ['legacy-ngacn', 'http://imgs.ngacn.cc/attachments/mon_201903/26/x.jpg', 'https://img.example.test/att'],
    ['outside-host-untouched', 'https://i.imgur.example/attachments/x.jpg', base],
    ['protocol-relative', '//img.nga.cn/attachments/mon_202006/03/a.png', base],
    ['not-an-attachment-path', 'https://img.nga.cn/other/a.png', base],
  ]
  for (const [name, src, useBase, note] of rehostCases) {
    emit('attachments', {
      name: `rehost-legacy-${name}`,
      fn: 'rehostLegacyAttachment',
      input: { src, base: useBase },
      expected: rehostLegacyAttachment(src, useBase),
      ...(note === undefined ? {} : { note }),
    })
  }

  const thumbnailCases: [string, string, string][] = [
    ['under-base', `${base}/mon_202608/07/x.png`, base],
    ['already-thumb', `${base}/mon_202608/07/x.png.thumb.jpg`, base],
    ['medium-to-thumb', `${base}/mon_202608/07/x.png.medium.jpg`, base],
    ['outside-host', 'https://i.imgur.com/abc.png', base],
  ]
  for (const [name, url, useBase] of thumbnailCases) {
    emit('attachments', {
      name: `thumbnail-url-${name}`,
      fn: 'thumbnailUrl',
      input: { url, base: useBase },
      expected: thumbnailUrl(url, useBase),
    })
  }

  /** 2025-05-26 17:33 (UTC+8)，取自 read-comment-noimg fixture 的第 3 楼。 */
  const postedAt = 1748252025
  /** 2025-05-26 23:30 (UTC+8) = UTC 已跨天、北京时间还没跨天。 */
  const lateNight = Date.UTC(2025, 4, 26, 15, 30) / 1000

  const urlCases: [
    name: string,
    ref: { src: string; needsAttachBase: boolean },
    options: { base: string; postedAt?: number },
    note?: string,
  ][] = [
    [
      'absolute-outside',
      { src: 'https://i.imgur.example/attachments/x.jpg', needsAttachBase: false },
      { base },
    ],
    [
      'legacy-domain-rehosted',
      {
        src: 'https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png',
        needsAttachBase: false,
      },
      { base },
    ],
    ['relative-with-base', { src: 'mon_202607/21/a.jpg', needsAttachBase: true }, { base }],
    [
      'relative-with-other-base',
      { src: 'mon_202607/21/a.jpg', needsAttachBase: true },
      { base: 'https://img.example.test/att' },
      '目标基址只从响应来：换基址就换域名',
    ],
    [
      'relative-strips-thumbnail',
      { src: 'mon_202607/21/a.jpg.medium.jpg', needsAttachBase: true },
      { base },
    ],
    [
      'noimg-needs-dated-directory',
      { src: '-7Qd36d-8aydZbT1kShs-13i.jpg', needsAttachBase: true },
      { base, postedAt },
      '[noimg] 没有日期目录，按发帖时间补 mon_YYYYMM/DD/',
    ],
    [
      'dated-directory-is-utc-plus-8',
      { src: 'a.jpg', needsAttachBase: true },
      { base, postedAt: lateNight },
      '日期目录按论坛时区（UTC+8）算，不跟设备时区',
    ],
    [
      'already-dated',
      { src: 'mon_202607/21/a.jpg', needsAttachBase: true },
      { base, postedAt },
    ],
    ['no-posted-at-no-guess', { src: 'a.jpg', needsAttachBase: true }, { base }],
    ['leading-slashes-trimmed', { src: '//a.jpg', needsAttachBase: true }, { base }],
  ]
  for (const [name, ref, options, note] of urlCases) {
    emit('attachments', {
      name: `attachment-url-${name}`,
      fn: 'attachmentUrl',
      input: { ref, options },
      expected: attachmentUrl(ref, options),
      ...(note === undefined ? {} : { note }),
    })
  }

  const fileNameCases: [string, string, string?][] = [
    ['with-query-and-hash', 'https://img.nga.cn/attachments/mon_202608/07/-7Qd36d-abcK2fT3cSu0-qo.jpg?x=1#f'],
    ['strip-thumb', 'https://img.nga.cn/attachments/mon_202608/07/a.jpg.thumb.jpg'],
    ['strip-medium', 'https://img.nga.cn/attachments/mon_202608/07/a.jpg.medium.jpg'],
    ['no-extension', 'https://example.com/image/12345', 'MediaStore 靠扩展名认类型，补 .jpg'],
    ['php-extension', 'https://example.com/a.php'],
    ['percent-escape', 'https://example.com/a%20b.png'],
    ['unsafe-chars', 'https://example.com/a"b|c.png'],
    ['no-name-hash-fallback', 'https://example.com/', '整段路径没名字时用 djb2 短哈希兜底'],
  ]
  for (const [name, url, note] of fileNameCases) {
    emit('attachments', {
      name: `image-file-name-${name}`,
      fn: 'imageFileName',
      input: url,
      expected: imageFileName(url),
      ...(note === undefined ? {} : { note }),
    })
  }

  for (const [name, fileName] of [
    ['png-uppercase', 'a.PNG'],
    ['webp', 'a.webp'],
    ['gif', 'a.gif'],
    ['unknown-extension', 'a.bin'],
    ['no-extension', 'noext'],
  ] as [string, string][]) {
    emit('attachments', {
      name: `image-mime-type-${name}`,
      fn: 'imageMimeType',
      input: fileName,
      expected: imageMimeType(fileName),
    })
  }
}

// ===========================================================================
// domain: deep-link
// ===========================================================================

function exportDeepLink(): void {
  const links: [string, string, string?][] = [
    ['read-tid-only', 'https://bbs.nga.cn/read.php?tid=45150945'],
    ['read-all-params', 'https://bbs.nga.cn/read.php?tid=45150945&page=3&pid=880123456&fav=1a2b3c'],
    [
      'read-unknown-params-skipped',
      'https://ngabbs.com/read.php?opt=512&page=2&authorid=41417929&tid=45150945',
    ],
    ['read-anchor-pid', 'https://bbs.nga.cn/read.php?tid=45150945&page=2#pid880123456Anchor'],
    ['read-query-pid-beats-anchor', 'https://bbs.nga.cn/read.php?tid=1&pid=222#pid333Anchor'],
    ['read-invalid-page-and-pid', 'https://bbs.nga.cn/read.php?tid=1&page=0&pid=-3'],
    ['read-non-numeric-page', 'https://bbs.nga.cn/read.php?tid=1&page=abc'],
    ['read-bad-fav-dropped', 'https://bbs.nga.cn/read.php?tid=1&fav=zzzz'],
    ['read-uppercase-fav', 'https://bbs.nga.cn/read.php?tid=1&fav=DEADBEEF'],
    ['read-html-escaped-amp', 'https://bbs.nga.cn/read.php?tid=1&amp;page=2'],
    ['read-missing-tid-pid-only', 'https://bbs.nga.cn/read.php?pid=880123456'],
    ['read-no-params', 'https://bbs.nga.cn/read.php'],
    ['read-tid-zero', 'https://bbs.nga.cn/read.php?tid=0'],
    ['read-tid-not-a-number', 'https://bbs.nga.cn/read.php?tid=abc'],

    ['thread-fid', 'https://bbs.nga.cn/thread.php?fid=650'],
    ['thread-stid', 'https://bbs.nga.cn/thread.php?stid=32871539'],
    ['thread-stid-wins', 'https://bbs.nga.cn/thread.php?fid=650&stid=32871539'],
    ['thread-negative-fid', 'https://bbs.nga.cn/thread.php?fid=-7'],
    ['thread-stid-zero-falls-back', 'https://bbs.nga.cn/thread.php?stid=0&fid=650'],
    ['thread-extra-params', 'https://bbs.nga.cn/thread.php?fid=650&page=4&order_by=postdatedesc'],
    ['thread-missing-id', 'https://bbs.nga.cn/thread.php'],
    ['thread-favor-only', 'https://bbs.nga.cn/thread.php?favor=1'],
    ['thread-fid-zero', 'https://bbs.nga.cn/thread.php?fid=0'],

    ['host-bbs-nga-cn', 'https://bbs.nga.cn/read.php?tid=1'],
    ['host-ngabbs-http', 'http://ngabbs.com/read.php?tid=1'],
    ['host-ngacn-cc', 'https://bbs.ngacn.cc/read.php?tid=1'],
    ['host-nga-178', 'http://nga.178.com/read.php?tid=1'],
    ['host-nga-donews', 'https://nga.donews.com/read.php?tid=1'],
    ['host-with-www', 'https://www.bbs.nga.cn/read.php?tid=1'],
    ['host-with-port', 'https://bbs.nga.cn:443/read.php?tid=1'],

    ['scheme-app-endpoint-in-host', 'ng2://read.php?tid=1&page=2'],
    ['scheme-app-endpoint-in-path', 'ng2:///thread.php?fid=650'],
    ['scheme-app-with-domain', 'ng2://bbs.nga.cn/read.php?tid=1'],
    ['scheme-app-dev', 'ng2-dev://read.php?tid=1'],
    ['no-scheme-with-host', 'bbs.nga.cn/read.php?tid=1'],
    ['no-scheme-endpoint-only', 'read.php?tid=1'],
    ['no-scheme-absolute-path', '/thread.php?fid=650'],
    ['surrounding-whitespace', '  https://bbs.nga.cn/read.php?tid=1  '],

    ['foreign-host', 'https://evil.example.com/read.php?tid=1'],
    ['foreign-host-lookalike', 'https://nga.cn.evil.com/read.php?tid=1'],
    ['foreign-host-no-scheme', 'evil.example.com/read.php?tid=1'],
    ['image-host-not-forum', 'https://img.nga.cn/read.php?tid=1'],
    ['scheme-ftp', 'ftp://bbs.nga.cn/read.php?tid=1'],
    ['scheme-javascript', 'javascript://read.php?tid=1'],
    ['unsupported-endpoint', 'https://bbs.nga.cn/nuke.php?func=ucp&uid=1'],
    ['host-root', 'https://bbs.nga.cn/'],
    ['host-without-path', 'https://bbs.nga.cn'],
    ['app-scheme-root', 'ng2:///', '冷启动时 expo-router 递进来的就是这个'],
    ['empty', ''],
    ['blank', '   '],
    ['random-sentence', '随便一句话'],
  ]

  for (const [name, input, note] of links) {
    emit('deep-link', {
      name: `parse-${name}`,
      fn: 'parseNgaLink',
      input,
      expected: parseNgaLink(input),
      ...(note === undefined ? {} : { note }),
    })
  }

  const paths: [string, NgaLink][] = [
    ['topic-only-tid', { kind: 'topic', tid: 45150945 }],
    ['topic-all-params', { kind: 'topic', tid: 1, page: 3, pid: 22, fav: 'ab12' }],
    ['topic-pid-only', { kind: 'topic', tid: 1, pid: 22 }],
    ['board', { kind: 'board', id: 650, boardKind: 'board' }],
    ['collection', { kind: 'board', id: 32871539, boardKind: 'collection' }],
  ]
  for (const [name, link] of paths) {
    emit('deep-link', {
      name: `path-${name}`,
      fn: 'ngaLinkPath',
      input: link,
      expected: ngaLinkPath(link),
    })
  }
}

// ===========================================================================
// domain: vote
// ===========================================================================

/** 站上真帖（tid=47331456，2026-08-08 快照）。 */
const REAL_VOTE =
  '208133~华为~208134~美国高通~max_select~1~end~1793891155~_208133~123,0,138~_208134~15,0,0'
const VOTE_TID = 47331456

function exportVote(): void {
  const cases: [name: string, raw: string, tid: number, note?: string][] = [
    ['real-single-choice', REAL_VOTE, VOTE_TID, '站上真帖：华为 vs 美国高通'],
    ['multi-select', '1~甲~2~乙~3~丙~max_select~2~_1~5,0,9~_2~3,0,0~_3~1,0,0', VOTE_TID],
    ['missing-max-select', '1~甲~2~乙~_1~1,0,1~_2~0,0,0', VOTE_TID],
    ['done-marks-chosen', '1~甲~2~乙~3~丙~max_select~2~done~1,3~_1~5,0,9~_2~3,0,0~_3~1,0,0', VOTE_TID],
    ['opt-result-after-vote', '1~甲~_1~1,0,1~opt~1', VOTE_TID],
    ['opt-result-after-end', '1~甲~_1~1,0,1~opt~3', VOTE_TID],
    ['priv-requirement', '1~甲~_1~1,0,1~priv~r-10_20', VOTE_TID, '门槛数值在下划线之后'],
    [
      'groups',
      '1~甲~2~乙~3~===第二组===~4~丙~_1~5,0,9~_2~3,0,0~_3~0,0,0~_4~2,0,0',
      VOTE_TID,
      '=== 开头是分组分隔行',
    ],
    ['groups-disabled-on-old-topic', '1~甲~2~===第二组===~_1~5,0,9~_2~3,0,0', 38056407],
    ['type-bet', '1~甲~_1~4,20,9~type~1', VOTE_TID],
    ['type-qa', '1~甲~_1~4,20,9~type~4', VOTE_TID],
    ['type-score-with-range', '1~画面~_1~4,18,4~type~2~min~1~max~5', VOTE_TID],
    ['empty', '', VOTE_TID],
    ['zero', '0', VOTE_TID],
    ['config-only', 'max_select~1~end~0', VOTE_TID],
    ['trailing-orphan-dropped', '1~甲~_1~3,0,3~max_select', VOTE_TID],
    ['missing-counts-default-zero', '1~甲~2~乙~_1~7,0,7', VOTE_TID],
  ]

  for (const [name, raw, tid, note] of cases) {
    const parsed = parseVote(raw, { tid })
    emit('vote', {
      name: `parse-${name}`,
      fn: 'parseVote',
      input: { raw, tid },
      expected: parsed === undefined ? null : parsed,
      ...(note === undefined ? {} : { note }),
    })
  }

  const closedCases: [string, string, number, number][] = [
    ['exactly-at-end', REAL_VOTE, VOTE_TID, 1793891155],
    ['one-second-after-end', REAL_VOTE, VOTE_TID, 1793891156],
    ['no-end-never-closes', '1~甲~_1~1,0,1', VOTE_TID, Number.MAX_SAFE_INTEGER],
  ]
  for (const [name, raw, tid, now] of closedCases) {
    const vote = parseVote(raw, { tid })!
    emit('vote', {
      name: `is-closed-${name}`,
      fn: 'isVoteClosed',
      input: { raw, tid, now },
      expected: isVoteClosed(vote, now),
      note: '输入先经 parseVote 再判；官方 atv = !x.end || __NOW <= x.end 的反面',
    })
  }

  for (const [name, votes, total] of [
    ['real', 123, 138],
    ['one-third', 1, 3],
    ['zero-total', 0, 0],
    ['all', 5, 5],
  ] as [string, number, number][]) {
    emit('vote', {
      name: `share-percent-${name}`,
      fn: 'voteSharePercent',
      input: { votes, total },
      expected: voteSharePercent(votes, total),
      note: '与网页版一样只保留一位小数且向下截断（((num/sum*1000)|0)/10）',
    })
  }
}

// ===========================================================================
// domain: query —— 出站参数编码（GBK 逐参数 opt-in）
// ===========================================================================

/**
 * 参数表 → 有序的 `[key, value]` 列表。
 *
 * **不能直接把参数对象当 input 落盘**：`stringifyStable` 会把键按字典序排掉，而
 * `buildQueryString` 拼出来的串是**插入序**——`post-form-same-rules` 那条
 * （`access_uid` 在 `access_token` 前）落盘后键序就反了，Kotlin 侧照文件顺序拼
 * 永远对不上 `expected`。数组的顺序 `stringifyStable` 会原样保留，所以用列表。
 * `undefined` 的值落成 `null`（两者在 `normalize` 里同档：剔除该参数）。
 */
function queryPairs(params: QueryParams): [string, unknown][] {
  return Object.entries(params).map(([key, value]) => [key, value === undefined ? null : value])
}

function exportQuery(): void {
  const cases: [name: string, params: QueryParams, note?: string][] = [
    ['plain-params', { fid: 650, page: 1 }],
    [
      'drop-empty-values',
      { fid: 650, stid: null, key: '', content: false, page: undefined },
      '空值参数必须删除：bool false = 不传、fid/stid 二选一都依赖它',
    ],
    ['true-becomes-one', { searchpost: true }],
    ['zero-is-kept', { page: 0 }],
    ['utf8-percent-encode', { key: '原神' }, 'thread.php 的 key 是 UTF-8'],
    ['gbk-marked-value', { author: gbk('原神') }, '同一接口的 author 却是 GBK（§0.5）'],
    ['gbk-empty-dropped', { author: gbk(''), fid: 1 }],
    ['empty-params', {}],
    ['post-form-same-rules', { access_uid: '123', access_token: 'abc', extra: '' }],
    [
      'gbk-block-word-payload',
      { data: gbk('1\r\n加密货币 测试\r\n42/张三') },
      '这一串对不上，网页版看到的就是乱码（block-word set_block_word）',
    ],
    ['gbk-emoji-not-in-table', { author: gbk('摸鱼😄') }, 'GBK 表外字符的落地形态'],
    ['key-is-encoded-too', { 'a b': 'c d' }],
  ]

  for (const [name, params, note] of cases) {
    emit('query', {
      name: `build-query-string-${name}`,
      fn: 'buildQueryString',
      input: { params: queryPairs(params) },
      expected: buildQueryString(params),
      ...(note === undefined ? {} : { note }),
    })
  }

  const gbkCases: [string, QueryParams | undefined][] = [
    ['with-gbk', { fid: 1, author: gbk('原神') }],
    ['without-gbk', { fid: 1, key: '原神' }],
    ['undefined-params', undefined],
    ['empty-gbk-value', { author: gbk('') }],
  ]
  for (const [name, params] of gbkCases) {
    emit('query', {
      name: `has-gbk-param-${name}`,
      fn: 'hasGbkParam',
      input: params === undefined ? null : { params: queryPairs(params) },
      expected: hasGbkParam(params),
      note: '命中 = 要声明 charset=GBK 并撤掉 __inchst=UTF8',
    })
  }
}

// ===========================================================================
// domain: filters —— 屏蔽规则匹配器（票 10）
// ===========================================================================

function exportFilters(): void {
  const rule = (over: Partial<FilterRule> & Pick<FilterRule, 'kind' | 'value'>): FilterRule => ({
    id: `${over.origin ?? 'local'}:${over.kind}:${over.value.toLowerCase()}`,
    origin: 'local',
    regex: false,
    ...over,
  })

  const normalizeCases: [string, string, boolean?][] = [
    ['trims-and-collapses-whitespace', '  张 \t 三  '],
    ['regex-keeps-whitespace-syntax', 'a\\s{2,}b', true],
    ['plain-empty', '   '],
  ]
  for (const [name, value, regex] of normalizeCases) {
    emit('filters', {
      name: `normalize-rule-value-${name}`,
      fn: 'normalizeRuleValue',
      input: { value, regex: regex === true },
      expected: normalizeRuleValue(value, regex),
    })
  }

  const idCases: [string, FilterRule['origin'], FilterRule['kind'], string][] = [
    ['local-keyword', 'local', 'keyword', '广告'],
    ['official-user-lowercases', 'official', 'user', 'XTL150OK'],
    ['local-category', 'local', 'category', '转帖'],
  ]
  for (const [name, origin, kind, value] of idCases) {
    emit('filters', {
      name: `filter-rule-id-${name}`,
      fn: 'filterRuleId',
      input: { origin, kind, value },
      expected: filterRuleId(origin, kind, value),
      note: '同来源、同类型、同内容即同一条规则；比对前小写',
    })
  }

  const categoryCases: [string, string][] = [
    ['two-tags', '[讨论][转帖]显卡涨价'],
    ['no-tag', '没有标签的标题'],
    ['empty-tag-dropped', '[]空标签'],
    ['nested-brackets-not-matched', '[外[内]层]标题'],
    ['too-long-tag-ignored', `[${'长'.repeat(21)}]标题`],
  ]
  for (const [name, title] of categoryCases) {
    emit('filters', {
      name: `topic-categories-${name}`,
      fn: 'topicCategories',
      input: title,
      expected: topicCategories(title),
    })
  }

  // ⚠ 非法正则那一档（`validateFilterRule({ value: '([', regex: true })`）**不导**：
  // 返回文案里嵌着 JS 引擎的 `SyntaxError.message`（V8 与 Hermes 都不保证一致，
  // JVM 的 PatternSyntaxException 更是另一套措辞），拿它对拍等于把引擎实现钉死。
  // Kotlin 侧只需保证「非法正则 → 返回以『正则表达式不合法：』开头的文案」，
  // 票 10 用手写单测锁前缀即可。
  const validateCases: [string, FilterRuleInput][] = [
    ['empty-keyword', { kind: 'keyword', value: '   ' }],
    ['empty-user', { kind: 'user', value: '' }],
    ['empty-category', { kind: 'category', value: '' }],
    ['valid-regex', { kind: 'keyword', value: '^\\[水\\]', regex: true }],
    ['same-string-as-plain-keyword-passes', { kind: 'keyword', value: '([' }],
    ['regex-flag-ignored-for-user', { kind: 'user', value: '([', regex: true }],
  ]
  for (const [name, input] of validateCases) {
    emit('filters', {
      name: `validate-filter-rule-${name}`,
      fn: 'validateFilterRule',
      input,
      expected: validateFilterRule(input) ?? null,
    })
  }

  const createCases: [string, FilterRuleInput, number][] = [
    ['keyword-trims', { kind: 'keyword', value: ' 广告 ' }, 102],
    ['regex-keyword', { kind: 'keyword', value: 'a\\s{2,}b', regex: true }, 100],
    ['user-with-uid', { kind: 'user', value: '张三', uid: 42 }, 101],
    ['regex-flag-only-for-keyword', { kind: 'category', value: '转帖', regex: true }, 103],
  ]
  for (const [name, input, nowSeconds] of createCases) {
    emit('filters', {
      name: `create-filter-rule-${name}`,
      fn: 'createFilterRule',
      input: { input, nowSeconds },
      expected: createFilterRule(input, nowSeconds),
    })
  }

  const existing = [
    createFilterRule({ kind: 'keyword', value: '广告' }, 100),
    createFilterRule({ kind: 'user', value: '张三' }, 101),
  ]
  const again = createFilterRule({ kind: 'keyword', value: ' 广告 ' }, 102)
  emit('filters', {
    name: 'upsert-filter-rule-replaces-and-moves-to-front',
    fn: 'upsertFilterRule',
    input: { rules: existing, rule: again },
    expected: upsertFilterRule(existing, again),
    note: '同一条规则重复添加是覆盖并挪到最前，不并存',
  })
  emit('filters', {
    name: 'upsert-filter-rule-new-goes-first',
    fn: 'upsertFilterRule',
    input: { rules: existing, rule: createFilterRule({ kind: 'category', value: '转帖' }, 103) },
    expected: upsertFilterRule(existing, createFilterRule({ kind: 'category', value: '转帖' }, 103)),
  })
  emit('filters', {
    name: 'remove-filter-rule-by-id',
    fn: 'removeFilterRule',
    input: { rules: existing, id: existing[0]!.id },
    expected: removeFilterRule(existing, existing[0]!.id),
  })
  emit('filters', {
    name: 'remove-filter-rule-missing-is-noop',
    fn: 'removeFilterRule',
    input: { rules: existing, id: 'local:keyword:别的' },
    expected: removeFilterRule(existing, 'local:keyword:别的'),
  })

  const matchCases: [string, FilterRule[], FilterSubject, string?][] = [
    ['keyword-in-title', [rule({ kind: 'keyword', value: '内部消息' })], { title: '爆个内部消息' }],
    [
      'keyword-in-content',
      [rule({ kind: 'keyword', value: '内部消息' })],
      { title: '闲聊', content: '这是内部消息，别外传' },
    ],
    [
      'keyword-miss',
      [rule({ kind: 'keyword', value: '内部消息' })],
      { title: '闲聊', content: '没什么可说的' },
    ],
    ['keyword-case-insensitive', [rule({ kind: 'keyword', value: 'Steam' })], { title: 'STEAM 夏促' }],
    [
      'regex-keyword-anchored',
      [rule({ kind: 'keyword', value: '^\\[水\\]', regex: true })],
      { title: '[水]今天吃什么' },
    ],
    [
      'regex-keyword-anchor-misses',
      [rule({ kind: 'keyword', value: '^\\[水\\]', regex: true })],
      { title: '闲聊 [水]今天吃什么' },
    ],
    [
      'plain-keyword-treats-metachars-literally',
      [rule({ kind: 'keyword', value: '^\\[水\\]' })],
      { title: '[水]今天吃什么' },
      '同一个串当普通关键词时是在找字面量',
    ],
    [
      'invalid-regex-never-matches-and-never-throws',
      [rule({ kind: 'keyword', value: '([未闭合', regex: true }), rule({ kind: 'keyword', value: '广告' })],
      { title: '([未闭合 的标题' },
      '写错的正则不能让整个列表白屏，也不能命中',
    ],
    [
      'invalid-regex-does-not-block-later-rules',
      [rule({ kind: 'keyword', value: '([未闭合', regex: true }), rule({ kind: 'keyword', value: '广告' })],
      { title: '这是广告' },
    ],
    ['user-exact-case-insensitive', [rule({ kind: 'user', value: 'xtl150ok' })], { author: 'XTL150OK' }],
    ['user-no-substring', [rule({ kind: 'user', value: 'xtl150ok' })], { author: 'xtl150ok2' }],
    [
      'user-rule-does-not-match-title',
      [rule({ kind: 'user', value: 'xtl150ok' })],
      { title: 'xtl150ok 说得对' },
    ],
    [
      'uid-beats-renamed-author',
      [rule({ kind: 'user', value: '旧名字', uid: 42 })],
      { author: '新名字', authorId: 42 },
      '带 uid 的用户规则以 uid 为准：改了名照样命中',
    ],
    ['uid-rule-still-matches-by-name', [rule({ kind: 'user', value: '旧名字', uid: 42 })], { author: '旧名字' }],
    ['uid-rule-misses-other-user', [rule({ kind: 'user', value: '旧名字', uid: 42 })], { author: '别人', authorId: 43 }],
    ['category-tag-in-title', [rule({ kind: 'category', value: '转帖' })], { title: '[转帖]某地新闻' }],
    [
      'category-not-from-content',
      [rule({ kind: 'category', value: '转帖' })],
      { title: '某地新闻', content: '转帖自隔壁' },
    ],
    [
      'category-needs-title',
      [rule({ kind: 'category', value: '转帖' })],
      { content: '[转帖]' },
      '楼层没有标题，分类规则对它天然不生效',
    ],
    [
      'first-matching-rule-wins',
      [rule({ kind: 'keyword', value: '广告' }), rule({ kind: 'keyword', value: '内部消息', origin: 'official' })],
      { title: '内部消息也是广告' },
      '本地规则排在官方之前时先报本地那条',
    ],
    ['empty-rule-table', [], { title: '随便什么' }],
    ['blank-rule-value-skipped', [rule({ kind: 'keyword', value: '  ' })], { title: 'x' }],
  ]
  for (const [name, rules, subject, note] of matchCases) {
    emit('filters', {
      name: `match-filter-rules-${name}`,
      fn: 'matchFilterRules',
      input: { rules, subject },
      expected: matchFilterRules(rules, subject) ?? null,
      ...(note === undefined ? {} : { note }),
    })
  }

  for (const [name, kind, value] of [
    ['user', 'user', '张三'],
    ['keyword', 'keyword', '广告'],
    ['category', 'category', '转帖'],
  ] as [string, FilterRule['kind'], string][]) {
    const item = rule({ kind, value })
    emit('filters', {
      name: `filter-match-text-${name}`,
      fn: 'filterMatchText',
      input: { rule: item },
      expected: filterMatchText(item),
    })
  }
}

// ===========================================================================
// domain: reply-chain —— 引用/回复链（票 10；input 先过 parseBBCode）
// ===========================================================================

const CHAIN_TID = 45150945

/** 引用块写法（NGA「引用」按钮的产物）。 */
const quoteOf = (pid: number, page: number, body: string): string =>
  `[quote][pid=${pid},${CHAIN_TID},${page}]Reply[/pid] [b]Post by [uid=41417929]张三[/uid] (2026-08-07 12:00):[/b]<br/>${body}[/quote]`

/** 回复头写法（NGA「回复」按钮的产物，没有 quote 容器）。 */
const replyTo = (pid: number, page: number): string =>
  `[b]Reply to [pid=${pid},${CHAIN_TID},${page}]Reply[/pid] Post by [uid=233]李四[/uid] (2026-08-07 13:00)[/b]<br/>`

/** Map/Set 装不进 JSON：拍平成有序的键值对列表（键升序，跨实现可复现）。 */
function serializeQuoteIndex(index: QuoteIndex): unknown {
  const byKey = <T,>(map: ReadonlyMap<number, T>): [number, T][] =>
    [...map.entries()].sort(([a], [b]) => a - b)
  return {
    quotes: byKey(index.quotes),
    quotedBy: byKey(index.quotedBy),
    loaded: [...index.loaded].sort((a, b) => a - b),
  }
}

function exportReplyChain(): void {
  const refCases: [string, string, string?][] = [
    ['quote-block', quoteOf(123456, 3, '原话') + '我的看法', 'tid 与页码来自 [pid] 的后两个参数'],
    ['reply-header', replyTo(777, 2) + '同意楼上', '[b]Reply to …[/b] 回复头也认成引用'],
    ['bare-pid-link-is-not-a-quote', `看看这楼 [pid=99,${CHAIN_TID},1]Reply[/pid] 说的`],
    [
      'nested-quote-only-outer',
      `[quote][pid=22,${CHAIN_TID},1]Reply[/pid] [b]Post by [uid=1]某人[/uid]:[/b][quote][pid=11,${CHAIN_TID},1]Reply[/pid]祖辈原话[/quote]父辈原话[/quote]`,
      '内层的 [pid] 是祖辈关系，算到本楼头上会把祖孙错接成父子',
    ],
    ['legacy-pid-without-tid', '[quote][pid=123]Reply[/pid]原话[/quote]'],
    ['bad-pid-arg-is-not-a-quote', '[quote][pid=abc]Reply[/pid]原话[/quote]'],
    ['plain-text-has-no-refs', '就一句话'],
  ]
  for (const [name, text, note] of refCases) {
    emit('reply-chain', {
      name: `extract-quote-refs-${name}`,
      fn: 'extractQuoteRefs',
      input: { text },
      expected: extractQuoteRefs(parseBBCode(text)),
      ...(note === undefined ? {} : { note }),
    })
  }

  for (const [name, text] of [
    ['quote-block', quoteOf(5, 1, '原话')],
    ['legacy-pid', '[quote][pid=123]Reply[/pid]原话[/quote]'],
    ['no-pid-inside', '[quote]光有原话[/quote]'],
  ] as [string, string][]) {
    const node = parseBBCode(text)[0]
    if (node?.type !== 'quote') throw new Error(`${name} 的第一个节点不是 quote`)
    emit('reply-chain', {
      name: `quote-ref-of-${name}`,
      fn: 'quoteRefOf',
      input: { text },
      expected: quoteRefOf(node) ?? null,
      note: 'input 是整段正文，取第一个节点（必是 quote）',
    })
  }

  const headerCases: [string, string][] = [
    ['reply-header', replyTo(777, 2) + '同意楼上'],
    ['plain-bold-is-not-a-header', '[b]重点[/b]'],
    ['handwritten-header-without-pid', '[b]Reply to 楼上[/b]'],
  ]
  for (const [name, text] of headerCases) {
    const node = parseBBCode(text)[0]!
    emit('reply-chain', {
      name: `is-reply-header-node-${name}`,
      fn: 'isReplyHeaderNode',
      input: { text },
      expected: isReplyHeaderNode(node),
    })
    emit('reply-chain', {
      name: `reply-header-ref-of-${name}`,
      fn: 'replyHeaderRefOf',
      input: { text },
      expected: replyHeaderRefOf(node) ?? null,
    })
  }

  const chainFixtures: [name: string, floors: QuoteIndexFloor[], tid: number | undefined, note?: string][] = [
    [
      'linear',
      [
        { pid: 1, lou: 0, content: '主楼' },
        { pid: 2, lou: 1, content: quoteOf(1, 1, '主楼原话') + '一楼' },
        { pid: 3, lou: 2, content: quoteOf(2, 1, '一楼原话') + '二楼' },
        { pid: 4, lou: 3, content: quoteOf(3, 1, '二楼原话') + '三楼' },
      ],
      CHAIN_TID,
    ],
    [
      'two-quoters',
      [
        { pid: 1, lou: 0, content: '主楼' },
        { pid: 2, lou: 1, content: quoteOf(1, 1, '主楼原话') + '顶' },
        { pid: 3, lou: 2, content: quoteOf(1, 1, '主楼原话') + '再顶' },
      ],
      CHAIN_TID,
      'quotes 记它引了谁，quotedBy 记谁引了它',
    ],
    [
      'self-and-cross-topic-quotes-dropped',
      [
        { pid: 7, lou: 3, content: quoteOf(7, 1, '自己') },
        { pid: 8, lou: 4, content: `[quote][pid=555,99999,1]Reply[/pid]别帖的话[/quote]` },
        { pid: 9, lou: 5, content: quoteOf(7, 1, 'a') + quoteOf(7, 1, 'b') },
      ],
      CHAIN_TID,
      '引用自己、跨帖引用不进索引；同目标引两次只记一条',
    ],
    [
      'downstream-sorted-by-lou',
      [
        { pid: 30, lou: 9, content: quoteOf(10, 1, '原话') },
        { pid: 20, lou: 4, content: quoteOf(10, 1, '原话') },
        { pid: 10, lou: 1, content: '被引的楼' },
      ],
      CHAIN_TID,
      '下游按楼号排，与楼层加载顺序无关',
    ],
    [
      'cross-page-quote-not-loaded',
      [{ pid: 100, lou: 21, content: quoteOf(66, 1, '第一页的原话') + '回它' }],
      CHAIN_TID,
      '被引楼不在已加载集合时，节点带 ref 供懒加载',
    ],
    [
      'legacy-quote-without-page',
      [{ pid: 100, lou: 5, content: '[quote][pid=66]Reply[/pid]老写法引用[/quote]' }],
      CHAIN_TID,
      '引用楼缺失且没有页码信息：节点照样在链上，只是没有定位手段',
    ],
    [
      'two-node-ring',
      [
        { pid: 1, lou: 1, content: quoteOf(2, 1, 'B 的话') + 'A' },
        { pid: 2, lou: 2, content: quoteOf(1, 1, 'A 的话') + 'B' },
      ],
      CHAIN_TID,
      '环引用靠 visited 掐断，不死循环',
    ],
    [
      'three-node-ring',
      [
        { pid: 1, lou: 1, content: quoteOf(3, 1, 'x') },
        { pid: 2, lou: 2, content: quoteOf(1, 1, 'x') },
        { pid: 3, lou: 3, content: quoteOf(2, 1, 'x') },
      ],
      CHAIN_TID,
    ],
    ['lonely-floor', [{ pid: 9, lou: 9, content: '就一句话' }], CHAIN_TID],
    [
      'no-tid-keeps-cross-topic-quote',
      [{ pid: 8, lou: 4, content: `[quote][pid=555,99999,1]Reply[/pid]别帖的话[/quote]` }],
      undefined,
      '不给 tid 就不做跨帖过滤',
    ],
  ]
  const startPids: Record<string, number[]> = {
    linear: [3, 1],
    'two-quoters': [1],
    'self-and-cross-topic-quotes-dropped': [9],
    'downstream-sorted-by-lou': [10],
    'cross-page-quote-not-loaded': [100],
    'legacy-quote-without-page': [100],
    'two-node-ring': [1],
    'three-node-ring': [2],
    'lonely-floor': [9],
    'no-tid-keeps-cross-topic-quote': [8],
  }
  for (const [name, floors, tid, note] of chainFixtures) {
    const options = tid === undefined ? {} : { tid }
    const index = buildQuoteIndex(floors, options)
    emit('reply-chain', {
      name: `build-quote-index-${name}`,
      fn: 'buildQuoteIndex',
      input: { floors, ...(tid === undefined ? {} : { tid }) },
      expected: serializeQuoteIndex(index),
      ...(note === undefined ? {} : { note }),
    })
    for (const startPid of startPids[name] ?? []) {
      emit('reply-chain', {
        name: `build-reply-chain-${name}-from-${startPid}`,
        fn: 'buildReplyChain',
        input: { floors, ...(tid === undefined ? {} : { tid }), startPid },
        expected: buildReplyChain(index, startPid),
        ...(note === undefined ? {} : { note }),
      })
      emit('reply-chain', {
        name: `chain-depth-of-${name}-from-${startPid}`,
        fn: 'chainDepthOf',
        input: { floors, ...(tid === undefined ? {} : { tid }), startPid },
        expected: chainDepthOf(index, startPid),
      })
    }
  }

  const stripCases: [string, string, string?][] = [
    ['quote-block', quoteOf(1, 1, '原话') + '我的看法', '剥掉引用块，留下本楼自己的话'],
    ['reply-header', replyTo(7, 1) + '同意楼上'],
    ['plain-bold-kept', '[b]重点[/b]内容', '普通粗体不剥，只剥 Reply to 回复头'],
    ['everything-stripped', quoteOf(1, 1, '原话')],
  ]
  for (const [name, text, note] of stripCases) {
    emit('reply-chain', {
      name: `strip-quote-markup-${name}`,
      fn: 'stripQuoteMarkup',
      input: { text },
      expected: stripQuoteMarkup(parseBBCode(text)),
      ...(note === undefined ? {} : { note }),
    })
  }
}

// ===========================================================================
// domain: money —— 金钱/威望显示换算（票 10，API 文档 §11.1）
// ===========================================================================

function exportMoney(): void {
  // ⚠ `splitMoney(NaN)` 不导：金样本里不允许非有限数字（README 规范 4），
  //   NaN 进不了 input。Kotlin 侧 `Double.NaN → 0` 由票 10 的手写单测锁。
  const copperCases: [string, number, string?][] = [
    ['zero', 0],
    ['one-copper', 1],
    ['one-silver', 100],
    ['one-gold', 10000],
    ['mixed', 123456, '12 金 34 银 56 铜'],
    ['negative', -12345, '负余额按绝对值拆，符号单独标出来'],
    ['fractional-truncated', 150.9, '小数先规整成整数铜币'],
  ]
  for (const [name, copperTotal, note] of copperCases) {
    emit('money', {
      name: `split-money-${name}`,
      fn: 'splitMoney',
      input: { copperTotal },
      expected: splitMoney(copperTotal),
      ...(note === undefined ? {} : { note }),
    })
    emit('money', {
      name: `format-money-${name}`,
      fn: 'formatMoney',
      input: { copperTotal },
      expected: formatMoney(copperTotal),
      note: '设计稿基础信息卡的 `金.银.铜` 文案',
    })
  }

  const reputationCases: [string, number, string?][] = [
    ['fifteen', 15],
    ['ten', 10],
    ['zero', 0],
    ['negative-real-sample', -11109, '真实样本 uid=2 的 rvrc'],
    ['one-hundred-twenty-four', 124],
  ]
  for (const [name, raw, note] of reputationCases) {
    const reputation = toReputation(raw)
    emit('money', {
      name: `to-reputation-${name}`,
      fn: 'toReputation',
      input: { raw },
      expected: reputation,
      ...(note === undefined ? {} : { note }),
    })
    emit('money', {
      name: `format-reputation-${name}`,
      fn: 'formatReputation',
      input: { reputation },
      expected: formatReputation(reputation),
      note: '固定一位小数（设计稿楼层头 `威望 1.0`）',
    })
  }
}

// ===========================================================================
// domain: hot-topics —— 本地热帖聚合（票 10）
// ===========================================================================

function exportHotTopics(): void {
  const NOW = 1_800_000_000

  const topic = (
    tid: number,
    overrides: Partial<HotTopicCandidate> & { postedAgo?: number } = {},
  ): HotTopicCandidate => {
    const { postedAgo = 3600, ...rest } = overrides
    return { tid, replies: 0, postedAt: NOW - postedAgo, lastPostAt: NOW - 60, ...rest }
  }

  const cases: [
    name: string,
    pages: HotTopicCandidate[][],
    windowHours: number | undefined,
    note?: string,
  ][] = [
    [
      'sorted-by-replies',
      [[topic(1, { replies: 10 }), topic(2, { replies: 300 }), topic(3, { replies: 42 })]],
      undefined,
    ],
    [
      'ties-broken-by-last-post-then-tid',
      [
        [
          topic(3, { replies: 5, lastPostAt: NOW - 100 }),
          topic(1, { replies: 5, lastPostAt: NOW - 10 }),
          topic(4, { replies: 5, lastPostAt: NOW - 100 }),
          topic(2, { replies: 5, lastPostAt: NOW - 100 }),
        ],
      ],
      undefined,
      '回复数相同按最后回复时间降序，再相同按 tid 升序——结果必须确定',
    ],
    [
      'window-boundary-inclusive',
      [
        [
          topic(1, { postedAgo: 24 * 3600 }),
          topic(2, { postedAgo: 24 * 3600 + 1 }),
          topic(3, { postedAgo: 10 }),
        ],
      ],
      undefined,
      '恰好 24h 含边界，过线 1 秒就出局',
    ],
    [
      'bumped-grave-does-not-enter',
      [[topic(1, { postedAgo: 300 * 24 * 3600, lastPostAt: NOW - 5, replies: 9999 })]],
      undefined,
      '窗口过滤看发帖时间而不是最后回复',
    ],
    [
      'custom-window-one-hour',
      [[topic(1, { postedAgo: 2 * 3600 }), topic(2, { postedAgo: 30 * 60 })]],
      1,
    ],
    [
      'custom-window-24-hours',
      [[topic(1, { postedAgo: 2 * 3600 }), topic(2, { postedAgo: 30 * 60 })]],
      24,
    ],
    [
      'dedup-across-pages',
      [
        [topic(1, { replies: 7 }), topic(2, { replies: 3 })],
        [topic(1, { replies: 7 }), topic(3, { replies: 5 })],
      ],
      undefined,
      '置顶主题每页都会再回来一次',
    ],
    [
      'shortcut-and-jump-url-excluded',
      [
        [
          topic(1, { replies: 100, shortcut: { kind: 'board', id: 650 } }),
          topic(2, { replies: 50, jumpUrl: 'https://nga.178.com/misc/lottery.html' }),
          topic(3, { replies: 1 }),
        ],
      ],
      undefined,
      '合集/镜像行与外链活动主题不是讨论串，不进榜',
    ],
    [
      'zero-posted-at-filtered-out',
      [[topic(1, { postedAt: 0 }), topic(2)]],
      undefined,
      'postedAt 解析失败退到 0 的坏条目被窗口自然挡掉',
    ],
    ['no-pages', [], undefined],
    ['all-pages-empty', [[], []], undefined],
  ]

  for (const [name, pages, windowHours, note] of cases) {
    const options = windowHours === undefined ? { now: NOW } : { now: NOW, windowHours }
    emit('hot-topics', {
      name: `aggregate-hot-topics-${name}`,
      fn: 'aggregateHotTopics',
      input: { pages, options },
      expected: aggregateHotTopics(pages, options),
      ...(note === undefined ? {} : { note }),
    })
  }
}

// ===========================================================================
// domain: api/* —— 端点解析器
// ===========================================================================

function netFixtureText(name: NetFixtureName): string {
  return decodeResponseBody(readNetFixtureBytes(name), netFixtureContentType(name))
}

function apiFixtureText(name: ApiFixtureName): string {
  return decodeResponseBody(readApiFixtureBytes(name), apiFixtureContentType(name))
}

type EnvelopePart = 'data' | 'root'

interface PipelineInput {
  readonly text: string
  readonly envelope: EnvelopeShape
  readonly part: EnvelopePart
  readonly args?: Record<string, unknown>
}

interface ValueInput {
  readonly value: unknown
  readonly args?: Record<string, unknown>
}

function pipeline(text: string, shape: EnvelopeShape, part: EnvelopePart): unknown {
  const envelope = parseNgaJson(text, 'golden', shape)
  return part === 'root' ? envelope.root : envelope.data
}

/** fixture → 管线 → 解析器。 */
function emitApiFixtureCase(
  domain: string,
  name: string,
  fn: string,
  text: string,
  options: {
    readonly envelope?: EnvelopeShape
    readonly part?: EnvelopePart
    readonly args?: Record<string, unknown>
    readonly note?: string
    readonly parse: (value: unknown) => unknown
  },
): void {
  const shape = options.envelope ?? 'wrapped'
  const part = options.part ?? 'data'
  const input: PipelineInput = {
    text,
    envelope: shape,
    part,
    ...(options.args === undefined ? {} : { args: options.args }),
  }
  emit(domain, {
    name,
    fn,
    input,
    expected: outcome(() => options.parse(pipeline(text, shape, part))),
    ...(options.note === undefined ? {} : { note: options.note }),
  })
}

/** 直接给解析器喂一个已经是 JSON 的值（不走管线）。 */
function emitApiValueCase(
  domain: string,
  name: string,
  fn: string,
  value: unknown,
  parse: (value: unknown) => unknown,
  note?: string,
  args?: Record<string, unknown>,
): void {
  const input: ValueInput = { value, ...(args === undefined ? {} : { args }) }
  emit(domain, {
    name,
    fn,
    input,
    expected: outcome(() => parse(value)),
    ...(note === undefined ? {} : { note }),
  })
}

const TOPIC_DETAIL_ARGS = { context: 'golden' } as const
const USER_PROFILE_ARGS = { nowSeconds: 1786100000 } as const

function exportApiTopicList(): void {
  const fixtures: [name: string, fixture: ApiFixtureName][] = [
    ['board-fid-7', 'threadListLounge'],
    ['search-key', 'threadSearchKey'],
    ['search-sixth-sense', 'threadSearchSixthSense'],
    ['favor-topics', 'favorTopics'],
    ['user-topics', 'threadUserTopics'],
    ['user-replies', 'threadUserReplies'],
    ['user-replies-end', 'threadUserRepliesEnd'],
    ['board-fid-414-output11', 'threadListBusyVerbose'],
  ]
  for (const [name, fixture] of fixtures) {
    emitApiFixtureCase('api/topic-list', name, 'parseTopicList', apiFixtureText(fixture), {
      parse: parseTopicList,
      note: API_FIXTURES[fixture].note,
    })
  }
  emitApiFixtureCase('api/topic-list', 'board-fid-650', 'parseTopicList', netFixtureText('threadList'), {
    parse: parseTopicList,
    note: NET_FIXTURES.threadList.note,
  })

  const structureCases: [string, unknown, string?][] = [
    ['has-t', { __T: {} }, '空版块也有 __T:{}——一条主题都没有 ≠ 没有这个结构'],
    ['has-f', { __F: { name: '原神' } }],
    ['has-rows', { __ROWS: 0 }],
    ['foreign-json', { code: 0, msg: '', result: [] }],
    ['empty-object', {}],
    ['null', null],
    ['array', []],
  ]
  for (const [name, data, note] of structureCases) {
    emitApiValueCase(
      'api/topic-list',
      `has-topic-list-structure-${name}`,
      'hasTopicListStructure',
      data,
      (value) => hasTopicListStructure(value),
      note,
    )
  }

  const rejectCases: [string, { data: unknown; fakeError?: { code: string; message: string } }, string?][] = [
    ['valid-list', { data: { __T: {}, __F: {} } }],
    [
      'foreign-json',
      { data: { code: 0, msg: '' } },
      '能洗成 JSON 但不是主题列表 → 当 kind=parse 继续轮换，坏组合进不了成功组合缓存',
    ],
    ['no-data', { data: undefined }],
    [
      'fake-error-is-normal-end',
      { data: { __MESSAGE: {} }, fakeError: { code: '?', message: '2048:没有符合条件的结果' } },
      '翻到底了是正常终止，不是坏组合',
    ],
  ]
  for (const [name, envelope, note] of rejectCases) {
    const result = rejectNonTopicList({ root: envelope.data, ...envelope } as never)
    emit('api/topic-list', {
      name: `reject-non-topic-list-${name}`,
      fn: 'rejectNonTopicList',
      input: {
        data: envelope.data === undefined ? null : envelope.data,
        ...(envelope.fakeError === undefined ? {} : { fakeError: envelope.fakeError }),
      },
      expected: result === undefined ? null : result,
      ...(note === undefined ? {} : { note }),
    })
  }

  emit('api/topic-list', {
    name: 'server-empty-topic-list',
    fn: 'serverEmptyTopicList',
    input: null,
    expected: serverEmptyTopicList(),
    note: '服务端明确回「没有符合条件的结果」时的空列表：listStructure 是 true（话说清楚了，只是没内容）',
  })

  const topic = (tid: number): Topic => ({
    tid,
    subject: `主题 ${tid}`,
    titleStyle: { bold: false, italic: false, underline: false },
    author: 'nga_user',
    anonymous: false,
    replies: 0,
    postedAt: 1786000000,
    lastPostAt: 1786000000,
    locked: false,
    hasAttachment: false,
    isCollection: false,
    isBoardMirror: false,
    denied: false,
  })
  const page = (topics: number[], rows = 40): TopicList => ({
    topics: topics.map(topic),
    subBoards: [],
    totalRows: rows,
    rowsPerPage: 20,
    totalPages: 2,
    listStructure: true,
  })
  const pages = [page([1, 2, 3]), page([2, 3, 4])]
  emitApiValueCase(
    'api/topic-list',
    'merge-topic-pages-dedupes',
    'mergeTopicPages',
    pages,
    (value) => mergeTopicPages(value as TopicList[]),
    '置顶主题与版块镜像行每页都会再回来一次（实测 fid=-7 第 1、2 页重叠 20 条），必须按 tid 去重',
  )
}

function exportApiTopicDetail(): void {
  const fixtures: [name: string, fixture: ApiFixtureName][] = [
    ['anonymous-hot-reply', 'readAnonymousHotReply'],
    ['comment-noimg', 'readComment'],
    ['attachments', 'readAttachments'],
    ['board-head', 'readBoardHead'],
  ]
  for (const [name, fixture] of fixtures) {
    emitApiFixtureCase('api/topic-detail', name, 'parseTopicDetail', apiFixtureText(fixture), {
      args: TOPIC_DETAIL_ARGS,
      parse: (value) => parseTopicDetail(value, TOPIC_DETAIL_ARGS),
      note: API_FIXTURES[fixture].note,
    })
  }
  emitApiFixtureCase(
    'api/topic-detail',
    'read-thread-jsvar',
    'parseTopicDetail',
    netFixtureText('readThread'),
    {
      args: TOPIC_DETAIL_ARGS,
      parse: (value) => parseTopicDetail(value, TOPIC_DETAIL_ARGS),
      note: NET_FIXTURES.readThread.note,
    },
  )

  const avatarCases: [string, unknown, string?][] = [
    ['plain-url', 'https://img.nga.178.com/avatars/a.jpg'],
    ['escaped-slashes', 'https:\\/\\/img.nga.cn\\/avatars\\/a.jpg'],
    ['json-multi', '{"0":"https:\\/\\/img.nga.cn\\/avatars\\/a.jpg","1":"https://x/b.jpg"}'],
    ['empty', ''],
    ['not-a-string', 42],
    ['no-url-inside', '{"0":""}'],
  ]
  for (const [name, raw, note] of avatarCases) {
    const parsed = parseAvatarUrl(raw)
    emit('api/topic-detail', {
      name: `parse-avatar-url-${name}`,
      fn: 'parseAvatarUrl',
      input: { raw },
      expected: parsed === undefined ? null : parsed,
      ...(note === undefined ? {} : { note }),
    })
  }
}

function exportApiBoardTree(): void {
  emitApiFixtureCase(
    'api/board-tree',
    'home-category',
    'parseBoardTree',
    apiFixtureText('homeCategory'),
    {
      envelope: 'bare',
      part: 'root',
      parse: parseBoardTree,
      note:
        API_FIXTURES.homeCategory.note +
        '；数据横跨顶层 data 与 other（图标清单、公告、推荐版块都在 other 里），解析拿的是 root',
    },
  )
  emitApiValueCase(
    'api/board-tree',
    'not-an-object-throws',
    'parseBoardTree',
    'nope',
    (value) => parseBoardTree(value),
    '顶层不是对象 → kind=parse',
  )
  emitApiValueCase(
    'api/board-tree',
    'no-board-throws',
    'parseBoardTree',
    { data: {}, other: {} },
    (value) => parseBoardTree(value),
    '一个版块都没解析出来 → kind=parse（等价于被封，该继续用本地缓存）',
  )

  const announcements = [
    { id: 'a', title: '限时活动', startAt: 1786000000, endAt: 1786100000 },
    { id: 'b', title: '长期公告' },
    { id: 'c', title: '未来的', startAt: 1900000000 },
  ]
  for (const [name, now] of [
    ['inside-window', 1786050000_000],
    ['after-window', 1786200000_000],
    ['before-any', 1000000000_000],
  ] as [string, number][]) {
    const picked = pickActiveAnnouncement(announcements, now)
    emit('api/board-tree', {
      name: `pick-active-announcement-${name}`,
      fn: 'pickActiveAnnouncement',
      input: { announcements, now },
      expected: picked === undefined ? null : picked,
      note: 'now 是毫秒，服务端字段是秒',
    })
  }
}

function exportApiBoardFavor(): void {
  for (const [name, fixture] of [
    ['list', 'forumFavorList'],
    ['empty', 'forumFavorEmpty'],
  ] as [string, ApiFixtureName][]) {
    emitApiFixtureCase('api/board-favor', name, 'parseBoardFavorites', apiFixtureText(fixture), {
      parse: parseBoardFavorites,
      note: API_FIXTURES[fixture].note,
    })
  }
  emitApiValueCase(
    'api/board-favor',
    'write-ok',
    'parseBoardFavorites',
    { '0': '操作成功' },
    parseBoardFavorites,
    'action=add|del 成功时 data["0"] 是一句文本，不是数组',
  )

  for (const [name, input] of [
    ['positive', '650'],
    ['negative', '-7'],
    ['whitespace', '  650  '],
    ['zero', '0'],
    ['not-a-number', 'abc'],
    ['empty', ''],
    ['float', '65.5'],
  ] as [string, string][]) {
    const parsed = parseBoardIdInput(input)
    emit('api/board-favor', {
      name: `parse-board-id-input-${name}`,
      fn: 'parseBoardIdInput',
      input,
      expected: parsed === undefined ? null : parsed,
    })
  }
}

function exportApiTopicFavor(): void {
  for (const [name, fixture] of [
    ['folders', 'favorFolders'],
    ['folders-empty', 'favorFoldersEmpty'],
    ['new-folder', 'favorNewFolder'],
  ] as [string, ApiFixtureName][]) {
    emitApiFixtureCase('api/topic-favor', name, 'parseFavoriteFolders', apiFixtureText(fixture), {
      parse: parseFavoriteFolders,
      note: API_FIXTURES[fixture].note,
    })
  }
}

function exportApiNotifications(): void {
  emitApiFixtureCase(
    'api/notifications',
    'get-all-empty',
    'parseNotificationFeed',
    apiFixtureText('notiGetAllEmpty'),
    {
      parse: parseNotificationFeed,
      note: API_FIXTURES.notiGetAllEmpty.note + '；data["0"] 是空串而不是对象',
    },
  )

  // 条目级向量按 API 文档 §9.1 + 两份研报的口径构造（测试账号抓不到带数据的样本）
  const replyToTopic = {
    '0': 1,
    '1': 60123456,
    '2': '留白，嗯',
    '5': '体感消费不一直这样吗？',
    '6': 44191387,
    '7': 812345678,
    '8': 812340000,
    '9': 1786100000,
    '10': 3,
  }
  const mentionInReply = {
    '0': '8',
    '1': '60234567',
    '2': '海豚音一号',
    '5': '显卡又开始涨价了',
    '6': '46186286',
    '7': '812350001',
    '9': '1786090000',
  }
  const newMessage = { '0': 10, '2': '版务组', '5': '关于你举报的楼层', '9': 1786080000 }

  const vectors: [string, unknown, string?][] = [
    [
      'three-boxes-merged',
      { '0': { unread: 2, '0': { '0': mentionInReply, '1': replyToTopic }, '1': [newMessage], '2': '' } },
      '三个容器合并解出，按时间戳降序',
    ],
    ['reply-full-fields', { '0': { '0': [replyToTopic] } }],
    ['string-number-fields', { '0': { '0': [mentionInReply] } }, '服务端偶尔把数字写成字符串'],
    ['message-without-tid-pid', { '0': { '1': [newMessage] } }, '短信类用 0 占位仍能生成稳定 ID'],
    [
      'broken-entries-skipped',
      { '0': { '0': [{ '2': '没类型' }, { '0': 1, '2': '没时间戳' }, replyToTopic, 42, null] } },
      '缺类型码或时间戳的条目跳过，坏条目不带崩整份',
    ],
    ['empty-string-box', { '0': '' }],
  ]
  for (const [name, data, note] of vectors) {
    emitApiValueCase('api/notifications', name, 'parseNotificationFeed', data, parseNotificationFeed, note)
  }

  for (const type of [1, 2, 3, 4, 7, 8, 10, 11, 17, 0, 99]) {
    emit('api/notifications', {
      name: `notification-kind-${type}`,
      fn: 'notificationKind',
      input: { type },
      expected: notificationKind(type),
      note: 'API 文档 §9.1 的类型码枚举；认不出的进 other',
    })
  }
}

function exportApiUserProfile(): void {
  for (const [name, fixture] of [
    ['user', 'ucpUser'],
    ['admin', 'ucpAdmin'],
    ['missing', 'ucpMissing'],
    ['avatar-only', 'ucpAvatar'],
  ] as [string, ApiFixtureName][]) {
    emitApiFixtureCase('api/user-profile', name, 'parseUserProfile', apiFixtureText(fixture), {
      args: USER_PROFILE_ARGS,
      parse: (value) => {
        const parsed = parseUserProfile(value, USER_PROFILE_ARGS)
        return parsed === undefined ? null : parsed
      },
      note: API_FIXTURES[fixture].note,
    })
  }
  emitApiFixtureCase(
    'api/user-profile',
    'capture-net-ucp-user',
    'parseUserProfile',
    netFixtureText('ucpUser'),
    {
      args: USER_PROFILE_ARGS,
      parse: (value) => {
        const parsed = parseUserProfile(value, USER_PROFILE_ARGS)
        return parsed === undefined ? null : parsed
      },
      note: NET_FIXTURES.ucpUser.note,
    },
  )
}

function exportApiSearch(): void {
  for (const [name, fixture] of [
    ['board-search-key', 'forumSearchKey'],
    ['board-search-none', 'forumSearchNone'],
  ] as [string, ApiFixtureName][]) {
    emitApiFixtureCase('api/search', name, 'parseBoardSearch', apiFixtureText(fixture), {
      parse: parseBoardSearch,
      note: API_FIXTURES[fixture].note,
    })
  }

  for (const [name, input] of [
    ['numeric-uid', '41417929'],
    ['username', 'BugenZhao'],
    ['mixed', 'user123'],
    ['chinese', '春曰影'],
    ['blank', '   '],
    ['zero', '0'],
  ] as [string, string][]) {
    const parsed = parseUserSearchInput(input)
    emit('api/search', {
      name: `parse-user-search-input-${name}`,
      fn: 'parseUserSearchInput',
      input,
      expected: parsed === undefined ? null : parsed,
      note: '只有整段都是数字才算 uid——NGA 用户名可以带数字',
    })
  }
}

function exportApiBlockWord(): void {
  const parseCases: [string, unknown, string?][] = [
    [
      'real-shape',
      { '0': '1\r\n加密货币 私聊出\r\n42/gerraerd 907/Apprivorisor' },
      'API 文档 §11.5：第 2 行关键词、第 3 行 uid/用户名 对',
    ],
    ['version-line-only', { '0': '1' }],
    ['empty-string', { '0': '' }],
    ['empty-object', {}],
    ['undefined', null],
    ['user-without-uid-and-slash-in-name', { '0': '1\r\n\r\n某人 42/a/b' }],
    ['lf-only', { '0': '1\n关键词\n42/某人' }, '服务端换行符不保证是 \\r\\n'],
    ['string-data', '1\r\n词\r\n'],
  ]
  for (const [name, data, note] of parseCases) {
    emitApiValueCase('api/block-word', `parse-${name}`, 'parseBlockWords', data, parseBlockWords, note)
  }

  const serializeCases: [string, BlockWordList, string?][] = [
    [
      'words-and-users',
      { words: ['加密货币', '私聊出'], users: [{ uid: 42, name: 'gerraerd' }, { name: '无 uid 的老数据' }] },
    ],
    ['empty-list', { words: [], users: [] }, '清空屏蔽词靠的就是写一张空表（三行结构要保留）'],
  ]
  for (const [name, list, note] of serializeCases) {
    emit('api/block-word', {
      name: `serialize-${name}`,
      fn: 'serializeBlockWords',
      input: list,
      expected: serializeBlockWords(list),
      ...(note === undefined ? {} : { note }),
    })
  }

  for (const [name, value, label] of [
    ['blank', '  ', undefined],
    ['contains-space', '内部 消息', undefined],
    ['ok', '内部消息', undefined],
    ['empty-with-label', '', '用户名'],
  ] as [string, string, string | undefined][]) {
    const result = label === undefined ? blockWordError(value) : blockWordError(value, label)
    emit('api/block-word', {
      name: `error-${name}`,
      fn: 'blockWordError',
      input: { text: value, ...(label === undefined ? {} : { label }) },
      expected: result === undefined ? null : result,
      note: '空格是表里的分隔符，带空白的词写上去会被服务端拆成两条',
    })
  }
}

function exportApiSubBoard(): void {
  for (const attributes of [7, 558, 542, 2606, 2590, 4654, 0, 40, 41, 4655]) {
    emit('api/sub-board', {
      name: `state-${attributes}`,
      fn: 'subBoardState',
      input: { attributes },
      expected: subBoardState(attributes),
      note: 'attributes 魔法数（API 文档 §13 第 13 条）：命中表 = 已订阅；>40 才可改',
    })
  }
  for (const action of ['subscribe', 'block'] as const) {
    for (const filterType of [0, 1] as const) {
      emit('api/sub-board', {
        name: `option-param-${action}-type-${filterType}`,
        fn: 'subBoardOptionParam',
        input: { action, filterType },
        expected: subBoardOptionParam(action, filterType),
        note: '参数名本身就是操作；type=0 时整个反过来',
      })
    }
  }
  for (const action of ['subscribe', 'block'] as const) {
    const state = subBoardState(4654)
    emit('api/sub-board', {
      name: `next-state-${action}`,
      fn: 'nextSubBoardState',
      input: { state, action },
      expected: nextSubBoardState(state, action),
      note: '服务端不回新的 attributes，本地按动作落状态，可改性不变',
    })
  }
}

function exportApiFields(): void {
  const orderedCases: [string, unknown, string?][] = [
    ['string-number-keys', { '10': 'j', '2': 'c', '0': 'a' }, '__output=8 的习惯（§0.6）'],
    ['real-array', ['a', 'c', 'j'], '__output=11 的 __T 是真数组——不认它整页会静默变 0 条'],
    ['empty-array', []],
    ['empty-object', {}],
    // ⚠ 非数字键之间必须取**字典序**（这里是 a 先于 b）：`stringifyStable` 会把对象键排成
    // 字典序，于是「JS 对象字面量的插入序」这个信息在文件里根本存不下来。写成 `{ b: 2, …, a: 1 }`
    // 的话 expected 是 `b, a`，而任何从这份 JSON 读回入参的实现（Kotlin 侧）只能得到 `a, b`，
    // 这条 case 就成了对不上的死局（票 04 发现，与 `query` domain 那次同源：
    // 规范化会抹掉顺序，靠顺序的用例不能拿对象当入参）。
    // 「插入序 ≠ 字典序时仍保持插入序」那一半跨不过 JSON，由 Kotlin 侧手写单测锁（FieldsTest）。
    [
      'mixed-keys',
      { a: 1, '1': 'x', b: 2, '0': 'y' },
      '非数字键排在数字键后面，保持原有顺序（键序只能是字典序，见导出器注释）',
    ],
    ['null', null],
    ['string', 'abc'],
    ['number', 42],
  ]
  for (const [name, value, note] of orderedCases) {
    emit('api/fields', {
      name: `ordered-entries-${name}`,
      fn: 'orderedEntries',
      input: { value },
      expected: orderedEntries(value),
      ...(note === undefined ? {} : { note }),
    })
    emit('api/fields', {
      name: `ordered-values-${name}`,
      fn: 'orderedValues',
      input: { value },
      expected: orderedValues(value),
    })
  }

  const record = {
    name: '  原神  ',
    empty: '   ',
    subject: '&lt;第六感&gt;那个小孩',
    emojiSubject: '&amp;#55357;&amp;#56836;',
    count: 12,
    countText: ' 34 ',
    float: 3.9,
    bad: 'abc',
    zero: 0,
    nested: {},
  }
  for (const key of ['name', 'empty', 'subject', 'missing', 'count', 'nested']) {
    const value = str(record as Record<string, unknown>, key)
    emit('api/fields', {
      name: `str-${key.toLowerCase()}`,
      fn: 'str',
      input: { record, key },
      expected: value === undefined ? null : value,
    })
  }
  for (const key of ['subject', 'emojiSubject', 'name', 'missing']) {
    const value = text(record as Record<string, unknown>, key)
    emit('api/fields', {
      name: `text-${key.toLowerCase()}`,
      fn: 'text',
      input: { record, key },
      expected: value === undefined ? null : value,
      note: 'subject 也会被 HTML 转义，走与正文同一套两轮解码',
    })
  }
  for (const key of ['count', 'countText', 'float', 'bad', 'zero', 'missing', 'nested']) {
    const value = int(record as Record<string, unknown>, key)
    emit('api/fields', {
      name: `int-${key.toLowerCase()}`,
      fn: 'int',
      input: { record, key },
      expected: value === undefined ? null : value,
    })
  }
  for (const [name, value] of [
    ['zero', 0],
    ['positive', 7],
    ['negative', -7],
    ['undefined', undefined],
  ] as [string, number | undefined][]) {
    const result = nonZero(value)
    emit('api/fields', {
      name: `non-zero-${name}`,
      fn: 'nonZero',
      input: { value: value === undefined ? null : value },
      expected: result === undefined ? null : result,
      note: 'NGA 分不清「字段缺省」与「填 0」（普通版块常带 stid:0）',
    })
  }
}

// ===========================================================================
// 写盘
// ===========================================================================

function exportAll(): Map<string, string> {
  files.clear()
  counts.clear()

  exportSanitize()
  exportEnvelope()
  exportErrors()
  exportDecodeBody()
  exportWeb()
  exportEntities()
  exportBBCode()
  exportDice()
  exportAnonymous()
  exportTitleStyle()
  exportAttachments()
  exportDeepLink()
  exportVote()
  exportQuery()
  exportFilters()
  exportReplyChain()
  exportMoney()
  exportHotTopics()

  exportApiTopicList()
  exportApiTopicDetail()
  exportApiBoardTree()
  exportApiBoardFavor()
  exportApiTopicFavor()
  exportApiNotifications()
  exportApiUserProfile()
  exportApiSearch()
  exportApiBlockWord()
  exportApiSubBoard()
  exportApiFields()

  // 索引：Kotlin 侧靠它枚举，不必在 classpath 上遍历目录
  const domains: Record<string, string[]> = {}
  for (const path of [...files.keys()].sort()) {
    const slash = path.lastIndexOf('/')
    const domain = path.slice(0, slash)
    const name = path.slice(slash + 1, -'.json'.length)
    ;(domains[domain] ??= []).push(name)
  }
  const index = {
    generator: 'scripts/export-goldens.mts',
    note: '生成物，勿手改；改 TS 源后跑 pnpm goldens:export 重生成',
    total: files.size,
    domains,
  }
  files.set('index.json', `${stringifyStable(index, '')}\n`)

  return files
}

/** 清掉旧产物（README.md 是手写的，留着）。 */
function cleanOutputDir(root: string): void {
  mkdirSync(root, { recursive: true })
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.isFile() && entry.name === 'README.md') continue
    rmSync(join(root, entry.name), { recursive: true, force: true })
  }
}

function writeAll(root: string, output: ReadonlyMap<string, string>): void {
  cleanOutputDir(root)
  for (const [relative, content] of output) {
    const target = join(root, relative)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, content, 'utf8')
  }
}

it('导出金样本并验证幂等', () => {
  const first = exportAll()
  writeAll(GOLDENS_ROOT, first)

  // 幂等：再跑一遍产出必须逐字节相同，且盘上的内容与它一致
  const second = exportAll()
  expect([...second.keys()].sort()).toEqual([...first.keys()].sort())
  for (const [relative, content] of second) {
    expect(content, relative).toBe(first.get(relative))
    expect(readFileSync(join(GOLDENS_ROOT, relative), 'utf8'), relative).toBe(content)
  }

  const summary = [...counts.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([domain, count]) => `${domain}: ${count}`)
  // eslint-disable-next-line no-console
  console.log(`\n金样本导出完成，共 ${first.size - 1} 条：\n  ${summary.join('\n  ')}\n`)
})
