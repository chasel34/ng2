import { describe, expect, it, vi } from 'vitest'

import { NgaError } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { checkIn } from './check-in'

/** 用内联 JSON 应答的假传输层，顺带把请求录下来。 */
function jsonTransport(body: string) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: 'text/javascript; charset=UTF-8',
      body: new TextEncoder().encode(body),
    }
  })
  return { transport, requests }
}

describe('checkIn', () => {
  it('打的是 nuke.php?__lib=check_in&__act=check_in，没有额外参数', async () => {
    const { transport, requests } = jsonTransport('{"data":{"0":"操作成功"}}')

    await checkIn(createNgaFetcher({ transport }))

    expect(requests).toHaveLength(1)
    expect(requests[0]?.method).toBe('POST')
    expect(requests[0]?.url).toContain('/nuke.php?')
    expect(requests[0]?.url).toContain('__lib=check_in')
    expect(requests[0]?.url).toContain('__act=check_in')
  })

  it('首次签到成功：alreadyCheckedIn 为 false，带上服务端原话', async () => {
    const { transport } = jsonTransport('{"data":{"0":"签到成功，获得 12 个铜币"}}')

    await expect(checkIn(createNgaFetcher({ transport }))).resolves.toEqual({
      alreadyCheckedIn: false,
      message: '签到成功，获得 12 个铜币',
    })
  })

  it('「今天已经签到」是假错误，按成功处理并标出来', async () => {
    const { transport } = jsonTransport('{"error":{"0":"你今天已经签到过了"}}')

    await expect(checkIn(createNgaFetcher({ transport }))).resolves.toEqual({
      alreadyCheckedIn: true,
      message: '你今天已经签到过了',
    })
  })

  it('未登录这类真错误照抛，不当成签到成功', async () => {
    const { transport } = jsonTransport('{"error":{"0":"你必须先登录论坛"}}')

    await expect(checkIn(createNgaFetcher({ transport }))).rejects.toThrow(NgaError)
  })
})
