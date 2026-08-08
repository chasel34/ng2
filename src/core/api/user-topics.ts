/**
 * 「某人的主题 / 某人的回复」（API 文档 §2 的后两个场景，仍然是 `thread.php`）。
 *
 * 和版块列表共用 `parseTopicList`——响应形状一模一样，回复列表只是每条多一个 `__P`
 * （那条回复本身，见 `Topic.reply`）。所以搜索票（15）拿 `fetchTopicList`/`fetchUserTopics`
 * 与这里的分页判据直接用即可，不必再写一份 thread.php 解析。
 *
 * 两处只有在这条路径上才踩得到的坑，都写在下面的注释里：
 * 分页不能看 `totalPages`，去重不能按 tid。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { parseTopicList } from './topic-list'
import type { Topic, TopicList } from './types'

/** 抽屉里那两个入口：我发的主题 / 我发的回复。 */
export type UserPostKind = 'topics' | 'replies'

export interface FetchUserTopicsOptions {
  readonly uid: number
  readonly kind: UserPostKind
  /** 从 1 起 */
  readonly page: number
  readonly signal?: AbortSignal
}

/**
 * 拉一页某人的主题或回复（`POST thread.php?authorid=<uid>[&searchpost=1]`）。
 *
 * 用 `authorid` 而不是 `author`：按用户名筛要把名字按 GBK 编码（API 文档 §0.5），
 * 而我们从楼层/抽屉进来时手里本来就有 uid。
 */
export async function fetchUserTopics(
  fetchNga: NgaFetcher,
  options: FetchUserTopicsOptions,
): Promise<TopicList> {
  const { uid, kind, page, signal } = options

  const result = await fetchNga({
    path: 'thread.php',
    query: {
      authorid: uid,
      page,
      ...(kind === 'replies' ? { searchpost: 1 } : {}),
    },
    ...(signal === undefined ? {} : { signal }),
  })

  if (!isRecord(result.data)) {
    // 翻过头时服务端回的是 error「2048:没有符合条件的结果」，那是假错误白名单里的一条
    // （core/net 的 FAKE_ERROR_MESSAGES），意思是「到底了」而不是「出错了」
    if (result.fakeError !== undefined) return parseTopicList({})
    throw new NgaError({ kind: 'parse', message: '用户主题列表响应里没有 data', via: result.via })
  }
  return parseTopicList(result.data)
}

/**
 * 还有没有下一页。
 *
 * 两个显然的判据都不能用：
 * - **`totalPages` 不行**：回复列表的 `__ROWS` 是空串，总数根本算不出来。
 * - **「这一页装满了没有」也不行**：实测 searchpost 每页只回 18~19 条（页大小写着 35），
 *   却还有后续页——按装满与否判会在第 1 页就停下。
 *
 * 所以只认「这一页一条都没有」：翻过头时服务端要么回空 `__T`，要么回
 * 「没有符合条件的结果」（上面已经归一成空页）。
 */
export function hasMoreUserPosts(page: TopicList): boolean {
  return page.topics.length > 0
}

/**
 * 把攒下的几页拼成一条列表。
 *
 * **不能复用 `mergeTopicPages`**：那个按 tid 去重，而在回复列表里同一个主题
 * 会正当地出现很多次（一个帖子里回了 10 层就是 10 条）。这里按 `reply.pid` 去重，
 * 没有 reply 的（主题列表）才退回 tid。
 */
export function mergeUserPostPages(pages: readonly TopicList[]): Topic[] {
  const seen = new Set<string>()
  const merged: Topic[] = []
  for (const page of pages) {
    for (const topic of page.topics) {
      const key = topic.reply === undefined ? `t${topic.tid}` : `p${topic.reply.pid}`
      if (seen.has(key)) continue
      seen.add(key)
      merged.push(topic)
    }
  }
  return merged
}
