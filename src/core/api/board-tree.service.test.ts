import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes } from './__fixtures__'
import { fetchBoardTree } from './board-tree'

/** 用真实抓包字节应答的假传输层，顺带把请求录下来。 */
function fixtureTransport() {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType('homeCategory'),
      body: readFixtureBytes('homeCategory'),
    }
  })
  return { transport, requests }
}

describe('fetchBoardTree', () => {
  it('打的是 app_api.php?__lib=home&__act=category', async () => {
    const { transport, requests } = fixtureTransport()

    await fetchBoardTree(createNgaFetcher({ transport }))

    expect(requests).toHaveLength(1)
    const [request] = requests
    expect(request?.method).toBe('POST')
    expect(request?.url).toContain('/app_api.php?')
    expect(request?.url).toContain('__lib=home')
    expect(request?.url).toContain('__act=category')
    // JSON 家族的格式参数由 net 层统一带上
    expect(request?.url).toContain('__output=8')
  })

  it('GBK 响应一路解码、清洗、解析成整棵树', async () => {
    const { transport } = fixtureTransport()

    const tree = await fetchBoardTree(createNgaFetcher({ transport }))

    expect(tree.categories.map((category) => category.name)).toContain('魔兽世界')
    expect(tree.categories.flatMap((c) => c.groups.flatMap((g) => g.boards)).length).toBe(677)
  })

  it('传得进 AbortSignal', async () => {
    const { transport, requests } = fixtureTransport()
    const controller = new AbortController()

    await fetchBoardTree(createNgaFetcher({ transport }), controller.signal)

    expect(requests[0]?.signal).toBe(controller.signal)
  })
})
