import { describe, expect, it, vi } from 'vitest'

import { decodeGb18030 } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import { parseTopicList } from './topic-list'
import { fetchUserTopics, hasMoreUserPosts, mergeUserPostPages } from './user-topics'

function fixtureTransport(name: ApiFixtureName) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType(name),
      body: readFixtureBytes(name),
    }
  })
  return { transport, requests }
}

const listOf = (name: ApiFixtureName) =>
  parseTopicList(JSON.parse(decodeGb18030(readFixtureBytes(name))).data)

describe('某人的主题（真实样本）', () => {
  const list = listOf('threadUserTopics')

  it('解出整页主题，总页数按 __ROWS 算', () => {
    expect(list.topics).toHaveLength(33)
    expect(list.totalRows).toBe(124)
    expect(list.rowsPerPage).toBe(35)
    expect(list.totalPages).toBe(4)
  })

  it('主题列表没有 __P，所以没有 reply', () => {
    expect(list.topics.every((topic) => topic.reply === undefined)).toBe(true)
  })
})

describe('某人的回复（真实样本）', () => {
  const list = listOf('threadUserReplies')

  it('每条都带上 __P 里那条回复本身', () => {
    const first = list.topics[0]
    expect(first?.tid).toBe(45150945)
    expect(first?.reply?.pid).toBe(861821212)
    expect(first?.reply?.content).toBe('测试回帖')
    expect(first?.reply?.postedAt).toBe(1774011037)
  })

  it('同一个主题会正当地重复出现（在一个帖子里回了很多层）', () => {
    const sameTopic = list.topics.filter((topic) => topic.tid === 45150945)
    expect(sameTopic.length).toBeGreaterThan(1)
    // 重复的是主题不是回复：pid 各不相同
    const pids = new Set(sameTopic.map((topic) => topic.reply?.pid))
    expect(pids.size).toBe(sameTopic.length)
  })

  it('过期/无权限的占位行标成 denied，subject 就是拒绝理由', () => {
    const denied = list.topics.filter((topic) => topic.denied)
    expect(denied.length).toBe(8)
    expect(denied[0]?.subject).toBe('帖子发布或回复时间超过限制')
    // 正常条目不带这个标
    expect(list.topics[0]?.denied).toBe(false)
  })

  it('__ROWS 是空串时退回 __T__ROWS，而不是算成「共 0 条」', () => {
    expect(list.totalRows).toBe(18)
    expect(list.topics).toHaveLength(18)
  })
})

describe('fetchUserTopics', () => {
  it('主题走 authorid，回复再加 searchpost=1', async () => {
    const topics = fixtureTransport('threadUserTopics')
    await fetchUserTopics(createNgaFetcher({ transport: topics.transport }), {
      uid: 41417929,
      kind: 'topics',
      page: 1,
    })
    expect(topics.requests[0]?.url).toContain('authorid=41417929')
    expect(topics.requests[0]?.url).not.toContain('searchpost')

    const replies = fixtureTransport('threadUserReplies')
    await fetchUserTopics(createNgaFetcher({ transport: replies.transport }), {
      uid: 41417929,
      kind: 'replies',
      page: 2,
    })
    expect(replies.requests[0]?.url).toContain('searchpost=1')
    expect(replies.requests[0]?.url).toContain('page=2')
  })

  it('打的是 thread.php，和版块列表同一个端点', async () => {
    const { transport, requests } = fixtureTransport('threadUserTopics')
    await fetchUserTopics(createNgaFetcher({ transport }), { uid: 1, kind: 'topics', page: 1 })
    expect(requests[0]?.url).toContain('/thread.php?')
  })

  it('翻过头时是「到底了」而不是报错——空页，`hasMoreUserPosts` 随即为假', async () => {
    const { transport } = fixtureTransport('threadUserRepliesEnd')

    const list = await fetchUserTopics(createNgaFetcher({ transport }), {
      uid: 41417929,
      kind: 'replies',
      page: 500,
    })

    expect(list.topics).toEqual([])
    expect(hasMoreUserPosts(list)).toBe(false)
  })

  it('响应连 data 带 error 都没有才算解析失败', async () => {
    const requests: HttpRequest[] = []
    const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
      requests.push(request)
      return {
        status: 200,
        contentType: 'text/javascript; charset=GBK',
        body: new TextEncoder().encode('{"data":"","time":1}'),
      }
    })

    await expect(
      fetchUserTopics(createNgaFetcher({ transport }), { uid: 1, kind: 'topics', page: 1 }),
    ).rejects.toThrow(/没有 data/)
  })
})

describe('hasMoreUserPosts', () => {
  const page = (count: number, rowsPerPage = 35) => ({
    topics: Array.from({ length: count }, () => ({}) as never),
    subBoards: [],
    totalRows: count,
    rowsPerPage,
    totalPages: 1,
  })

  it('只认「这一页一条都没有」', () => {
    expect(hasMoreUserPosts(page(35))).toBe(true)
    // 实测 searchpost 每页只回 18~19 条却还有后续页，按装满与否判会在第 1 页就停
    expect(hasMoreUserPosts(page(18))).toBe(true)
    expect(hasMoreUserPosts(page(0))).toBe(false)
  })
})

describe('mergeUserPostPages', () => {
  const topic = (tid: number, pid?: number) =>
    ({ tid, ...(pid === undefined ? {} : { reply: { pid, content: '', postedAt: 0 } }) }) as never
  const page = (...topics: unknown[]) =>
    ({ topics, subBoards: [], totalRows: 0, rowsPerPage: 35, totalPages: 1 }) as never

  it('回复按 pid 去重——同一个 tid 的多条回复都要留下', () => {
    const merged = mergeUserPostPages([page(topic(1, 10), topic(1, 11)), page(topic(1, 11))])
    expect(merged.map((item) => item.reply?.pid)).toEqual([10, 11])
  })

  it('没有回复的（主题列表）才按 tid 去重', () => {
    const merged = mergeUserPostPages([page(topic(1), topic(2)), page(topic(2), topic(3))])
    expect(merged.map((item) => item.tid)).toEqual([1, 2, 3])
  })
})
