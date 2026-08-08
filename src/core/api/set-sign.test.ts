import { describe, expect, it, vi } from 'vitest'

import { unescapeNgaText } from '../bbcode'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { updateSignature } from './set-sign'
import { parseUserProfile } from './user-profile'

function jsonTransport(body = '{"data":{"0":"操作成功"}}') {
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

/** 表单体是 percent-encoded 的，断言前先解回来。 */
const formOf = (request: HttpRequest | undefined) =>
  Object.fromEntries(new URLSearchParams(request?.body ?? '').entries())

describe('updateSignature', () => {
  it('打 nuke.php?__lib=set_sign&__act=set，uid 与 sign 走表单', async () => {
    const { transport, requests } = jsonTransport()

    await updateSignature(createNgaFetcher({ transport }), { uid: 42, signature: '一行签名' })

    expect(requests[0]?.method).toBe('POST')
    expect(requests[0]?.url).toContain('__lib=set_sign')
    expect(requests[0]?.url).toContain('__act=set')
    expect(formOf(requests[0])).toMatchObject({ uid: '42', sign: '一行签名' })
  })

  it('emoji / ZWJ / 变体选择符转成 UTF-16 十进制实体再提交', async () => {
    const { transport, requests } = jsonTransport()

    await updateSignature(createNgaFetcher({ transport }), {
      uid: 42,
      signature: 'A😂B❤️C👨‍👩‍👧‍👦',
    })

    expect(formOf(requests[0]).sign).toBe(
      'A&#55357;&#56834;B&#10084;&#65039;C' +
        '&#55357;&#56424;&#8205;&#55357;&#56425;&#8205;&#55357;&#56423;&#8205;&#55357;&#56422;',
    )
  })

  it('转义后的签名按资料接口的规矩读回来，与原文一致', async () => {
    const { transport, requests } = jsonTransport()
    const signature = '摸鱼中 🐟 请勿打扰 ❤️'

    await updateSignature(createNgaFetcher({ transport }), { uid: 42, signature })

    // 服务端存的就是提交上去的那串实体；资料接口回读时走 parseUserProfile
    const stored = formOf(requests[0]).sign ?? ''
    const profile = parseUserProfile({ '0': { uid: 42, username: '我', sign: stored } })
    expect(unescapeNgaText(profile?.signature ?? '')).toBe(signature)
  })

  it('清空签名传一个空格：空串会被当成「不传这个参数」丢掉', async () => {
    const { transport, requests } = jsonTransport()

    await updateSignature(createNgaFetcher({ transport }), { uid: 42, signature: '' })

    expect(formOf(requests[0]).sign).toBe(' ')
  })
})
