/**
 * 热帖聚合（CONTEXT.md「热帖」）——**不是服务端 API**。
 *
 * 客户端并发拉版块前若干页主题列表，在本地按 24 小时窗口过滤、按回复数排序
 * （功能文档 §2.2「热门话题」，MNGA/NGA-CLIENT 同款做法）。这里只做纯聚合：
 * 拉页与容错在 core/api，本函数拿到「已经拉回来的那几页」算榜单。
 *
 * 时间一律由调用方传入（秒级 unix 时间戳），函数里不取当前时间——
 * 同一份输入永远算出同一份榜单，才测得动。
 */

/**
 * 聚合需要看的最小字段集（`Topic` 的子集，泛型保真：传 `Topic` 进来出去还是 `Topic`）。
 * 秒级 unix 时间戳，与 `thread.php` 的 `postdate`/`lastpost` 一致。
 */
export interface HotTopicCandidate {
  readonly tid: number
  readonly replies: number
  readonly postedAt: number
  readonly lastPostAt: number
  /** 合集/版块镜像行：不是讨论串，回复数没有可比性，聚合时剔掉 */
  readonly shortcut?: unknown
  /** 外链活动主题：点开不是 read.php，不进榜 */
  readonly jumpUrl?: string
}

export interface AggregateHotTopicsOptions {
  /** 当前时刻，秒级 unix 时间戳。由调用方传入，纯函数不自己看表 */
  readonly now: number
  /** 时间窗口小时数，默认 24（spec §4：热帖仅 24h 档，留参数位是为了单测与将来加档） */
  readonly windowHours?: number
}

/** 默认窗口：24 小时（spec §4「热帖仅 24h 档」）。 */
export const HOT_WINDOW_HOURS = 24

/**
 * 把几页主题聚成热帖榜。
 *
 * - **窗口过滤看发帖时间**（`postedAt`）：榜单回答的是「过去 24 小时里冒出来的
 *   哪些新主题最热」，按最后回复过滤的话十年老坟顶一下也会进榜；
 * - 跨页按 tid 去重（置顶主题每页都会再回来一次，同 `mergeTopicPages` 的原因）；
 * - 排序：回复数降序 → 最后回复时间降序 → tid 升序，最后一档是为了让结果确定。
 */
export function aggregateHotTopics<T extends HotTopicCandidate>(
  pages: readonly (readonly T[])[],
  options: AggregateHotTopicsOptions,
): T[] {
  const windowSeconds = (options.windowHours ?? HOT_WINDOW_HOURS) * 3600
  const earliest = options.now - windowSeconds

  const seen = new Set<number>()
  const picked: T[] = []
  for (const page of pages) {
    for (const topic of page) {
      if (seen.has(topic.tid)) continue
      seen.add(topic.tid)
      if (topic.shortcut !== undefined || topic.jumpUrl !== undefined) continue
      // postedAt 缺省解析成 0，会被窗口自然挡掉；postedAt 在未来的（服务端时钟漂移）照收
      if (topic.postedAt < earliest) continue
      picked.push(topic)
    }
  }

  return picked.sort(
    (a, b) => b.replies - a.replies || b.lastPostAt - a.lastPostAt || a.tid - b.tid,
  )
}
