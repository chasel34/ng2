import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes } from './__fixtures__'
import { fetchTopicList } from './topic-list'

/** 用真实抓包字节应答的假传输层，顺带把请求录下来。 */
function fixtureTransport(body?: string) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType('threadListLounge'),
      body:
        body === undefined ? readFixtureBytes('threadListLounge') : new TextEncoder().encode(body),
    }
  })
  return { transport, requests }
}

const urlOf = async (
  options: Parameters<typeof fetchTopicList>[1],
): Promise<string> => {
  const { transport, requests } = fixtureTransport()
  await fetchTopicList(createNgaFetcher({ transport }), options)
  return requests[0]?.url ?? ''
}

describe('fetchTopicList', () => {
  it('普通版块传 fid、合集传 stid（二选一）', async () => {
    const board = await urlOf({ boardId: -7, kind: 'board', page: 1 })
    expect(board).toContain('/thread.php?')
    expect(board).toContain('fid=-7')
    expect(board).not.toContain('stid=')

    const collection = await urlOf({ boardId: 44618580, kind: 'collection', page: 1 })
    expect(collection).toContain('stid=44618580')
    expect(collection).not.toContain('fid=')
  })

  it('页码原样带上', async () => {
    expect(await urlOf({ boardId: -7, kind: 'board', page: 3 })).toContain('page=3')
  })

  it('按发帖时间排序才带 order_by，默认（最后回复）不带', async () => {
    expect(await urlOf({ boardId: -7, kind: 'board', page: 1, sort: 'postDate' })).toContain(
      'order_by=postdatedesc',
    )
    expect(await urlOf({ boardId: -7, kind: 'board', page: 1, sort: 'lastPost' })).not.toContain(
      'order_by',
    )
    expect(await urlOf({ boardId: -7, kind: 'board', page: 1 })).not.toContain('order_by')
  })

  it('精华区带 recommend=1 与 Android 同款的固定参数，sort 不生效', async () => {
    const url = await urlOf({ boardId: -7, kind: 'board', page: 1, recommend: true, sort: 'lastPost' })
    expect(url).toContain('recommend=1')
    expect(url).toContain('order_by=postdatedesc')
    expect(url).toContain('user=1')
  })

  it('不开精华区时不带 recommend', async () => {
    expect(await urlOf({ boardId: -7, kind: 'board', page: 1 })).not.toContain('recommend')
  })

  it('GBK 响应一路解码、清洗、解析成一页主题', async () => {
    const { transport } = fixtureTransport()

    const list = await fetchTopicList(createNgaFetcher({ transport }), {
      boardId: -7,
      kind: 'board',
      page: 1,
    })

    expect(list.topics).toHaveLength(51)
    expect(list.board?.name).toBe('网事杂谈')
  })

  it('传得进 AbortSignal', async () => {
    const { transport, requests } = fixtureTransport()
    const controller = new AbortController()

    await fetchTopicList(createNgaFetcher({ transport }), {
      boardId: -7,
      kind: 'board',
      page: 1,
      signal: controller.signal,
    })

    expect(requests[0]?.signal).toBe(controller.signal)
  })

  it('响应里没有 data 时报解析错，交给上层兜底', async () => {
    const { transport } = fixtureTransport('{"data":"","time":1786112241}')

    await expect(
      fetchTopicList(createNgaFetcher({ transport }), { boardId: -7, kind: 'board', page: 1 }),
    ).rejects.toThrow(/没有 data/)
  })
})
