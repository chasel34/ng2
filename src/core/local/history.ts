/**
 * 浏览历史与阅读进度的核心模型(CONTEXT.md「阅读进度」,spec §4)。
 *
 * 纯函数、零 RN 依赖:LRU/去重/进度前进的规则全在这里,SQLite 读写归
 * `src/store/history.ts` 适配器。列表是「新的在前」的有序数组——上限只有
 * 200 条,数组比 Map 省事,还天然就是历史页要的展示顺序。
 */

/** 设计稿历史页副标题:「本机记录 · 保留最近 200 条」。 */
export const HISTORY_LIMIT = 200

/** 历史里的一条主题(CONTEXT.md「主题」)。可 JSON 序列化,SQLite 一行存一条。 */
export interface HistoryEntry {
  readonly tid: number
  readonly subject: string
  /** 楼主名(已匿名还原);从非第 1 页进来时可能拿不到 */
  readonly author?: string
  /** 列表行标题后面那个灰色 `[版块名]` */
  readonly boardName?: string
  /** fav 码(CONTEXT.md「fav 码」),重新打开隐藏/过期主题时必带 */
  readonly favCode?: string
  /**
   * 已读到的最高楼层号(`lou`,0 = 主楼)。只前进不后退——
   * 重进主题回头翻前几楼不该把「读到 96 楼」倒退回「读到 3 楼」,
   * 「读完」状态也不该因为回头看一眼就丢(功能文档 §2 的「最高楼层」策略)。
   */
  readonly lastFloor: number
  /** 已知的最高楼层号(= 楼层总数 - 1),用来判断「读完」;有新回复时会涨 */
  readonly maxFloor: number
  /** 最近一次浏览,秒级 unix 时间戳;LRU 淘汰与展示排序都按它 */
  readonly updatedAt: number
}

/** 一次进入主题时上报的资料。楼层进度走 `advanceHistoryFloor`,这里只管元数据。 */
export interface TopicVisit {
  readonly tid: number
  readonly subject: string
  readonly author?: string
  readonly boardName?: string
  readonly favCode?: string
  /** 本次已知的最高楼层号(楼层总数 - 1);拿不到就不更新 */
  readonly maxFloor?: number
}

/**
 * 一次变更的结果。`changed` 为 false 时 `entries` 就是原数组,
 * 适配器据此跳过 SQLite 写入;`evictedTids` 是被 LRU 挤出去的主题,要连带删行。
 */
export interface HistoryUpdate {
  readonly entries: readonly HistoryEntry[]
  readonly changed: boolean
  readonly evictedTids: readonly number[]
}

const unchanged = (entries: readonly HistoryEntry[]): HistoryUpdate => ({
  entries,
  changed: false,
  evictedTids: [],
})

/**
 * 浏览一个主题:同主题**更新时间与资料而不是新增条目**(ticket 16 验收项),
 * 条目挪到最前;新主题插到最前,超过 200 条时把最老的挤出去。
 *
 * 楼层进度按「只前进」合并:`visit.maxFloor` 只会把已知上限往上抬。
 * 元数据(标题/楼主/版块名/fav 码)以新值优先,但新值缺席时保留旧值——
 * 从第 2 页直接进来拿不到楼主,不能把第一次记下的名字冲掉。
 */
export function upsertHistory(
  entries: readonly HistoryEntry[],
  visit: TopicVisit,
  now: number,
): HistoryUpdate {
  const existing = entries.find((entry) => entry.tid === visit.tid)

  const next: HistoryEntry = {
    tid: visit.tid,
    subject: visit.subject !== '' ? visit.subject : (existing?.subject ?? ''),
    lastFloor: existing?.lastFloor ?? 0,
    maxFloor: Math.max(existing?.maxFloor ?? 0, visit.maxFloor ?? 0),
    updatedAt: now,
    ...pickDefined('author', visit.author ?? existing?.author),
    ...pickDefined('boardName', visit.boardName ?? existing?.boardName),
    ...pickDefined('favCode', visit.favCode ?? existing?.favCode),
  }

  const kept = entries.filter((entry) => entry.tid !== visit.tid)
  const capped = [next, ...kept]
  const evicted = capped.slice(HISTORY_LIMIT)
  return {
    entries: capped.slice(0, HISTORY_LIMIT),
    changed: true,
    evictedTids: evicted.map((entry) => entry.tid),
  }
}

/**
 * 滚动时上报「看到了第 `lou` 楼」。楼层没前进就原样返回(`changed: false`)——
 * 滚动回调触发得很勤,不能每次都去写 SQLite;`updatedAt` 的「最近浏览」语义
 * 由进入主题时的 `upsertHistory` 负责,这里只管楼层数字。
 */
export function advanceHistoryFloor(
  entries: readonly HistoryEntry[],
  tid: number,
  lou: number,
  now: number,
): HistoryUpdate {
  const existing = entries.find((entry) => entry.tid === tid)
  // 条目必须先由 upsertHistory 建好;还没建就丢弃这次上报,别造出残缺行
  if (existing === undefined || lou <= existing.lastFloor) return unchanged(entries)

  const next: HistoryEntry = {
    ...existing,
    lastFloor: lou,
    // 楼层号只可能落在 0..maxFloor 里;真看到了更大的说明上限过时了,一起抬
    maxFloor: Math.max(existing.maxFloor, lou),
    updatedAt: now,
  }
  return {
    // 挪到最前:正在读的就是最近浏览的,内存里的顺序要与重启后按
    // updatedAt 重排的顺序一致,不然历史页在一次会话内外长得不一样
    entries: [next, ...entries.filter((entry) => entry.tid !== tid)],
    changed: true,
    evictedTids: [],
  }
}

/** 是否「读完」:读到了已知的最后一楼。空主题(只有主楼)读过主楼就算读完。 */
export function isHistoryFinished(entry: Pick<HistoryEntry, 'lastFloor' | 'maxFloor'>): boolean {
  return entry.lastFloor >= entry.maxFloor
}

/** 历史页右侧那格文案:「读完」或「读到 N 楼」(设计稿 LISTS.history)。 */
export function historyProgressLabel(
  entry: Pick<HistoryEntry, 'lastFloor' | 'maxFloor'>,
): string {
  if (isHistoryFinished(entry)) return '读完'
  return entry.lastFloor === 0 ? '读到主楼' : `读到 ${entry.lastFloor} 楼`
}

/** 第 `lou` 楼落在第几页(页码从 1 起)。主楼 lou=0 在第 1 页。 */
export function pageOfFloor(lou: number, rowsPerPage: number): number {
  const perPage = Math.max(1, Math.trunc(rowsPerPage))
  return Math.floor(Math.max(0, lou) / perPage) + 1
}

const MINUTE = 60
const HOUR = 3600
const DAY = 86400

/**
 * 历史页时间列的口径(照设计稿示例行:刚刚 / 12 分钟前 / 今天 20:14 /
 * 昨天 23:41 / 前天 / 更早直接给日期)。
 *
 * 一小时以内先走相对时间(跨没跨零点都一样,29 分钟前就是「29 分钟前」);
 * 再往前的「今天/昨天/前天」按本地日历日算而不是按 24 小时窗口——凌晨 0 点后,
 * 昨晚 9 点的记录该叫「昨天」而不是「今天」。`now` 由调用方注入,函数本身可单测。
 */
export function formatHistoryTime(updatedAt: number, now: number): string {
  const elapsed = now - updatedAt
  if (elapsed < MINUTE) return '刚刚'
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} 分钟前`

  const time = new Date(updatedAt * 1000)
  const dayDiff = calendarDayDiff(time, new Date(now * 1000))
  if (dayDiff <= 0) return `今天 ${clockOf(time)}`
  if (dayDiff === 1) return `昨天 ${clockOf(time)}`
  if (dayDiff === 2) return '前天'
  return dateOf(time)
}

/** `to` 与 `from` 隔了几个本地日历日(同一天为 0)。 */
function calendarDayDiff(from: Date, to: Date): number {
  const startOfDay = (date: Date) =>
    new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  return Math.round((startOfDay(to) - startOfDay(from)) / (DAY * 1000))
}

const pad2 = (value: number): string => String(value).padStart(2, '0')

const clockOf = (date: Date): string => `${pad2(date.getHours())}:${pad2(date.getMinutes())}`

const dateOf = (date: Date): string =>
  `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`

/** exactOptionalPropertyTypes 下拼可选字段:undefined 就干脆不放键。 */
function pickDefined<K extends string, V>(key: K, value: V | undefined): { [P in K]?: V } {
  return value === undefined ? {} : ({ [key]: value } as { [P in K]: V })
}
