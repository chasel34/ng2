import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes } from './__fixtures__'
import { DEFAULT_HOT_PAGES, fetchHotTopicPages } from './hot-topics'

/**
 * 用真实抓包字节应答的假传输层；`failPages` 里的页码直接断网。
 * 顺带把请求 URL 录下来,断言并发拉了哪几页。
 */
function fixtureTransport(failPages: readonly number[] = []) {
  const urls: string[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    urls.push(request.url)
    const page = Number(/[?&]page=(\d+)/.exec(request.url)?.[1])
    if (failPages.includes(page)) throw new Error(`page ${page} 断网`)
    return {
      status: 200,
      contentType: fixtureContentType('threadListLounge'),
      body: readFixtureBytes('threadListLounge'),
    }
  })
  return { transport, urls }
}

describe('fetchHotTopicPages', () => {
  it('并发拉前 N 页,页码 1..N', async () => {
    const { transport, urls } = fixtureTransport()

    const result = await fetchHotTopicPages(createNgaFetcher({ transport }), {
      boardId: -7,
      kind: 'board',
      pages: 3,
    })

    expect(result.pages).toHaveLength(3)
    expect(result.pagesTried).toBe(3)
    expect(result.failedPages).toEqual([])
    expect(urls.map((url) => /[?&]page=(\d+)/.exec(url)?.[1]).sort()).toEqual(['1', '2', '3'])
  })

  it('页数不传时用默认档', async () => {
    const { transport } = fixtureTransport()
    const result = await fetchHotTopicPages(createNgaFetcher({ transport }), {
      boardId: -7,
      kind: 'board',
    })
    expect(result.pagesTried).toBe(DEFAULT_HOT_PAGES)
  })

  it('部分页失败不整体失败:成功页照给,失败页码记下来', async () => {
    const { transport } = fixtureTransport([2])

    const result = await fetchHotTopicPages(createNgaFetcher({ transport }), {
      boardId: -7,
      kind: 'board',
      pages: 3,
    })

    expect(result.pages).toHaveLength(2)
    expect(result.failedPages).toEqual([2])
    expect(result.pages.every((page) => page.topics.length > 0)).toBe(true)
  })

  it('全部页都失败才抛错', async () => {
    const { transport } = fixtureTransport([1, 2, 3])

    await expect(
      fetchHotTopicPages(createNgaFetcher({ transport }), {
        boardId: -7,
        kind: 'board',
        pages: 3,
      }),
    ).rejects.toThrow()
  })
})
