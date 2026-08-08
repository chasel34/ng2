/**
 * 帖子缓存的核心模型（spec §4：浏览自动写 SQLite + LRU 上限，ADR-0002 里它是反封锁链
 * 倒数第二环）。纯 TS、零 RN 依赖：淘汰口径与展示文案在这里，SQLite 读写归
 * `src/store/topic-cache.ts` 适配器。
 *
 * 存储的粒度是**一页**（`read.php` 一次请求就是一页），但用户看到与操作的粒度是
 * **一个主题**：「我的缓存」列出的是主题，删也是整主题删，LRU 淘汰同样按主题整体走——
 * 只淘汰某个主题的第 3 页，留下 1、2、4 页，离线读到中间会突然断掉。
 */

/** 缓存最多留多少个主题。按主题数而不是页数：一个 200 页的长帖也只占一格。 */
export const TOPIC_CACHE_MAX_TOPICS = 100

/**
 * 缓存总字节数上限。存的是文本信封（正文 BBCode + 用户表），一页大约 30–120 KB，
 * 32 MB 够放几百页；图片走 expo-image 自己的磁盘缓存，不算在这里。
 */
export const TOPIC_CACHE_MAX_BYTES = 32 * 1024 * 1024

/** 缓存里的一页（不含正文本身，payload 只在 SQLite 里）。 */
export interface CachedPage {
  readonly tid: number
  /** 从 1 起 */
  readonly page: number
  readonly subject: string
  readonly boardName?: string
  /** fav 码（CONTEXT.md「fav 码」），离线打开隐藏/过期主题时要带回去 */
  readonly favCode?: string
  /** 这一页存了多少楼，「第 1 页 · 40 楼」那一格用 */
  readonly floors: number
  /** 写入时该主题共有多少页 */
  readonly totalPages: number
  /** payload 的字节数 */
  readonly bytes: number
  /** 最近一次写入或读取的时间（秒）。LRU 淘汰与列表排序都按它 */
  readonly usedAt: number
}

/** 「我的缓存」列表里的一行：同一主题的所有页聚合成一条。 */
export interface CachedTopic {
  readonly tid: number
  readonly subject: string
  readonly boardName?: string
  readonly favCode?: string
  /** 已缓存的页码，升序 */
  readonly pages: readonly number[]
  /** 主题总页数（以最近写入的那一页为准），用来说「缓存了 3/12 页」 */
  readonly totalPages: number
  /** 只缓存了一页时展示的楼数 */
  readonly floors: number
  readonly bytes: number
  readonly usedAt: number
}

/** exactOptionalPropertyTypes 下拼可选字段：undefined 就干脆不放键。 */
function pickDefined<K extends string, V>(key: K, value: V | undefined): { [P in K]?: V } {
  return value === undefined ? {} : ({ [key]: value } as { [P in K]: V })
}

/**
 * 把页级记录聚合成主题级列表，按最近使用倒序（= 「我的缓存」页的展示顺序）。
 *
 * 元数据（标题/版块名/fav 码/总页数）取**最近写入的那一页**的值：标题会被改，
 * 总页数会随新回复涨，新的那份更可信；但新的那份缺席时保留旧值——
 * 从第 2 页开始缓存的主题，版块名要靠更早的记录补。
 */
export function summarizeCachedPages(pages: readonly CachedPage[]): readonly CachedTopic[] {
  const byTid = new Map<number, CachedPage[]>()
  for (const page of pages) {
    const bucket = byTid.get(page.tid)
    if (bucket === undefined) byTid.set(page.tid, [page])
    else bucket.push(page)
  }

  const topics: CachedTopic[] = []
  for (const [tid, bucket] of byTid) {
    // 新的在前：元数据按这个顺序「先到先得」
    const newestFirst = [...bucket].sort((a, b) => b.usedAt - a.usedAt)
    const numbers = [...new Set(bucket.map((page) => page.page))].sort((a, b) => a - b)
    const newest = newestFirst[0]
    if (newest === undefined) continue

    topics.push({
      tid,
      subject: newestFirst.find((page) => page.subject !== '')?.subject ?? '',
      pages: numbers,
      totalPages: Math.max(newest.totalPages, numbers.at(-1) ?? 1),
      floors: newest.floors,
      bytes: bucket.reduce((sum, page) => sum + page.bytes, 0),
      usedAt: newest.usedAt,
      ...pickDefined('boardName', newestFirst.find((page) => page.boardName !== undefined)?.boardName),
      ...pickDefined('favCode', newestFirst.find((page) => page.favCode !== undefined)?.favCode),
    })
  }
  return topics.sort((a, b) => b.usedAt - a.usedAt)
}

export interface CacheLimits {
  readonly maxTopics?: number
  readonly maxBytes?: number
}

/**
 * 超限时该淘汰哪些主题（返回 tid，调用方连带删掉它的所有页）。
 *
 * 两条上限任一超了就从最久未用的开始淘汰，直到两条都满足。**最近使用的那个主题
 * 永远留着**：用户刚缓存完一个大帖，结果它自己把自己挤没了才是真的费解——
 * 单主题就超过字节上限时宁可暂时超额一点。
 */
export function planCacheEviction(
  topics: readonly CachedTopic[],
  limits: CacheLimits = {},
): readonly number[] {
  const maxTopics = limits.maxTopics ?? TOPIC_CACHE_MAX_TOPICS
  const maxBytes = limits.maxBytes ?? TOPIC_CACHE_MAX_BYTES

  // 最久未用的排前面：淘汰就从这一头开始吃
  const oldestFirst = [...topics].sort((a, b) => a.usedAt - b.usedAt)
  let count = oldestFirst.length
  let bytes = oldestFirst.reduce((sum, topic) => sum + topic.bytes, 0)

  const evicted: number[] = []
  for (const topic of oldestFirst) {
    if (count <= maxTopics && bytes <= maxBytes) break
    if (count <= 1) break
    evicted.push(topic.tid)
    count -= 1
    bytes -= topic.bytes
  }
  return evicted
}

/**
 * 一段文本存成 UTF-8 有多少字节。
 *
 * 不用 `new TextEncoder().encode(text).length`：那要为每一页正文额外分配一整个
 * 字节数组，而这里只是想要个数字（Hermes 的编码器支持面也一直是本项目的坑，
 * 见 `core/net/encoding/gb18030`）。代理对按整个码点算 4 字节，落单的代理项
 * （坏字符串）按 3 字节算，与实际编码器一致。
 */
export function utf8ByteLength(text: string): number {
  let bytes = 0
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index)
    if (code < 0x80) bytes += 1
    else if (code < 0x800) bytes += 2
    else if (code >= 0xd800 && code < 0xdc00 && index + 1 < text.length) {
      const next = text.charCodeAt(index + 1)
      if (next >= 0xdc00 && next < 0xe000) {
        bytes += 4
        index += 1
      } else bytes += 3
    } else bytes += 3
  }
  return bytes
}

/** 缓存总占用，「已占用 42.6 MB」那句副标题用。 */
export function cacheTotalBytes(topics: readonly CachedTopic[]): number {
  return topics.reduce((sum, topic) => sum + topic.bytes, 0)
}

const KB = 1024
const MB = 1024 * 1024
const GB = 1024 * 1024 * 1024

/** 设计稿缓存页的大小口径：「1.2 MB」「0.9 MB」。不足 1 MB 的给整数 KB。 */
export function formatCacheSize(bytes: number): string {
  const value = Math.max(0, Math.round(bytes))
  if (value < KB) return `${value} B`
  if (value < MB) return `${Math.round(value / KB)} KB`
  if (value < GB) return `${(value / MB).toFixed(1)} MB`
  return `${(value / GB).toFixed(2)} GB`
}

/** 连续页码压成 `[起, 止]` 区间。 */
function pageRanges(pages: readonly number[]): (readonly [number, number])[] {
  const ranges: [number, number][] = []
  for (const page of pages) {
    const last = ranges.at(-1)
    if (last !== undefined && page === last[1] + 1) last[1] = page
    else ranges.push([page, page])
  }
  return ranges
}

/** 区间多到摆不下时只列前两段，剩下的用「等 N 页」收尾。 */
const MAX_RANGE_GROUPS = 3

/**
 * 设计稿缓存行的页范围一格：「第 1 页 · 40 楼」「第 1–3 页」。
 *
 * 只缓存了一页时顺带报楼数（那时页范围本身没什么信息量）；
 * 缓存的页不连续（跳着手动缓存过几页）时按区间列出来。
 */
export function cachePagesLabel(topic: Pick<CachedTopic, 'pages' | 'floors'>): string {
  const pages = topic.pages
  if (pages.length === 0) return '空缓存'
  if (pages.length === 1) return `第 ${pages[0]} 页 · ${topic.floors} 楼`

  const ranges = pageRanges(pages)
  const shown = ranges
    .slice(0, MAX_RANGE_GROUPS)
    .map(([from, to]) => (from === to ? `${from}` : `${from}–${to}`))
    .join('、')
  return ranges.length > MAX_RANGE_GROUPS
    ? `第 ${shown} 等 ${pages.length} 页`
    : `第 ${shown} 页`
}
