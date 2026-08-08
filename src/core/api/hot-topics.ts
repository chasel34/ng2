/**
 * 热帖的取数侧：并发拉版块前若干页主题列表（功能文档 §2.2「热门话题」——
 * **不是服务端 API**，服务端只有普通的 thread.php 分页）。
 *
 * 失败页容错：拉 5 页坏 1 页，榜单照出——被封时本来就是这种半死不活的状态，
 * 部分页失败只意味着榜单不完整，不该把整个功能变成一张错误页。
 * 全部页都失败才抛错（抛第一页的错，它最能代表「这个版块拉不动」）。
 *
 * 聚合排序/过滤是纯本地逻辑，在 `core/local/hot-topics.ts`。
 */

import type { NgaFetcher } from '../net'
import { fetchTopicList } from './topic-list'
import type { TopicList } from './types'

/** 默认并发页数。功能文档 §2.2：客户端并发拉 5~10 页，取下界少打几枪（ADR-0002）。 */
export const DEFAULT_HOT_PAGES = 5

export interface FetchHotTopicPagesOptions {
  /** 版块 id：合集传 stid、普通版块传 fid（CONTEXT.md「合集」） */
  readonly boardId: number
  readonly kind: 'board' | 'collection'
  /** 并发拉多少页，默认 {@link DEFAULT_HOT_PAGES}（页数可配） */
  readonly pages?: number
  readonly signal?: AbortSignal
}

export interface HotTopicPages {
  /** 成功拉回来的页，按页码升序（聚合对页序不敏感，但稳定输出便于测试与缓存比对） */
  readonly pages: readonly TopicList[]
  /** 失败的页码（从 1 起），UI 用它提示「榜单不完整」 */
  readonly failedPages: readonly number[]
  /** 一共试了几页 */
  readonly pagesTried: number
}

/**
 * 并发拉前 `pages` 页。按最后回复排序（默认序）：24 小时内有动静的主题
 * 都聚在最前几页，这正是热帖窗口要扫的那片。
 */
export async function fetchHotTopicPages(
  fetchNga: NgaFetcher,
  options: FetchHotTopicPagesOptions,
): Promise<HotTopicPages> {
  const { boardId, kind, signal } = options
  const pagesTried = Math.max(1, options.pages ?? DEFAULT_HOT_PAGES)

  const settled = await Promise.allSettled(
    Array.from({ length: pagesTried }, (_, index) =>
      fetchTopicList(fetchNga, {
        boardId,
        kind,
        page: index + 1,
        ...(signal === undefined ? {} : { signal }),
      }),
    ),
  )

  const pages: TopicList[] = []
  const failedPages: number[] = []
  for (const [index, outcome] of settled.entries()) {
    if (outcome.status === 'fulfilled') pages.push(outcome.value)
    else failedPages.push(index + 1)
  }

  if (pages.length === 0) {
    const first = settled[0]
    throw first !== undefined && first.status === 'rejected'
      ? first.reason
      : new Error('热帖：一页都没拉回来')
  }

  return { pages, failedPages, pagesTried }
}
