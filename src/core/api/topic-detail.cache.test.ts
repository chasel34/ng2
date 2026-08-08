import { describe, expect, it, vi } from 'vitest'

import { createTopicCacheStrategy, type TopicCacheKey } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpTransport } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import { fetchTopicDetail, type CachedPageSnapshot } from './topic-detail'
import type { TopicDetail } from './types'

/**
 * 帖子缓存（20 票）的端到端：在线拉一页 → 攒下的信封进缓存 → 断网时同一页从缓存还回来。
 * 两趟走的是同一个 `fetchTopicDetail`，差别只在链上哪一档产出了信封。
 */

const TID = 44191387

/** 在线那一趟：转发抓包样本，与设备上同一条解码路径。 */
function onlineFetcher(name: ApiFixtureName) {
  const transport: HttpTransport = () =>
    Promise.resolve({
      status: 200,
      contentType: fixtureContentType(name),
      body: readFixtureBytes(name),
    })
  return createNgaFetcher({ transport })
}

/** 断网那一趟：链上只剩缓存档，一个字节都发不出去。 */
function offlineFetcher(pages: Map<string, string>) {
  const transport: HttpTransport = () => Promise.reject(new Error('断网'))
  const read = vi.fn((key: TopicCacheKey) => pages.get(`${key.tid}/${key.page}`))
  return {
    fetchNga: createNgaFetcher({
      transport,
      strategies: [createTopicCacheStrategy({ store: { read } })],
    }),
    read,
  }
}

/**
 * 匿名楼层的用户 key 带请求级前缀（每次请求换一个，见 topic-detail 的 `context`），
 * 两趟之间必然不同——比对前统一抹掉，比的是「同一份数据」而不是「同一次请求」。
 */
function normalizeContext(detail: TopicDetail): unknown {
  return JSON.parse(JSON.stringify(detail).replace(/[a-z0-9]+\.[a-z0-9]+,-/g, 'ctx,-')) as unknown
}

describe('自动缓存写入', () => {
  it('拿到一页就交出可缓存的快照：元数据 + 可还原的信封', async () => {
    const snapshots: CachedPageSnapshot[] = []
    const detail = await fetchTopicDetail(onlineFetcher('readAttachments'), {
      tid: TID,
      page: 1,
      favCode: 'abc123',
      onSnapshot: (snapshot) => snapshots.push(snapshot),
    })

    expect(snapshots).toHaveLength(1)
    expect(snapshots[0]).toMatchObject({
      tid: TID,
      page: 1,
      subject: detail.subject,
      floors: detail.floors.length,
      totalPages: detail.totalPages,
      favCode: 'abc123',
    })
    expect(JSON.parse(snapshots[0]?.payload ?? '')).toBeTypeOf('object')
  })

  it('只看该楼 / 只看某人是过滤视图，不写缓存', async () => {
    const snapshots: CachedPageSnapshot[] = []
    const onSnapshot = (snapshot: CachedPageSnapshot) => snapshots.push(snapshot)

    await fetchTopicDetail(onlineFetcher('readComment'), { tid: TID, page: 1, pid: 9, onSnapshot })
    await fetchTopicDetail(onlineFetcher('readComment'), {
      tid: TID,
      page: 1,
      authorId: 205511,
      onSnapshot,
    })

    expect(snapshots).toEqual([])
  })
})

describe('从缓存还原', () => {
  it('缓存还回来的一页与在线那一页除来源外完全一致', async () => {
    const pages = new Map<string, string>()
    const online = await fetchTopicDetail(onlineFetcher('readComment'), {
      tid: TID,
      page: 1,
      onSnapshot: (snapshot) => pages.set(`${snapshot.tid}/${snapshot.page}`, snapshot.payload),
    })
    expect(online.source).toBe('native')

    const offline = offlineFetcher(pages)
    const restored = await fetchTopicDetail(offline.fetchNga, { tid: TID, page: 1 })

    expect(restored.source).toBe('cache')
    expect(normalizeContext({ ...restored, source: 'native' })).toEqual(normalizeContext(online))
  })

  it('缓存档出的结果不再回写一遍（内容一模一样）', async () => {
    const pages = new Map<string, string>()
    await fetchTopicDetail(onlineFetcher('readComment'), {
      tid: TID,
      page: 1,
      onSnapshot: (snapshot) => pages.set(`${snapshot.tid}/${snapshot.page}`, snapshot.payload),
    })

    const snapshots: CachedPageSnapshot[] = []
    await fetchTopicDetail(offlineFetcher(pages).fetchNga, {
      tid: TID,
      page: 1,
      onSnapshot: (snapshot) => snapshots.push(snapshot),
    })

    expect(snapshots).toEqual([])
  })

  it('缓存里没有这一页时,断网就是断网', async () => {
    const offline = offlineFetcher(new Map())
    await expect(fetchTopicDetail(offline.fetchNga, { tid: TID, page: 9 })).rejects.toThrow()
    expect(offline.read).toHaveBeenCalledWith({ tid: TID, page: 9 })
  })
})
