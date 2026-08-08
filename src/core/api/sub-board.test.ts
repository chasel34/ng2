import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import {
  FILTERABLE_ATTRIBUTES_MIN,
  SUBSCRIBED_ATTRIBUTES,
  nextSubBoardState,
  setSubBoardOption,
  subBoardOptionParam,
  subBoardState,
} from './sub-board'
import type { SubBoard } from './types'

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

const formOf = (request: HttpRequest | undefined) =>
  Object.fromEntries(new URLSearchParams(request?.body ?? '').entries())

/** 真实样本（fid=-7 的 sub_forums）里的两种子版块。 */
const tidSubBoard: SubBoard = {
  id: 570,
  kind: 'board',
  fid: 570,
  name: '优惠信息 购物指南',
  filterId: 12700430,
  filterType: 1,
  attributes: 4654,
}
const fidSubBoard: SubBoard = {
  id: 414,
  kind: 'board',
  fid: 414,
  name: '游戏综合讨论',
  filterId: 414,
  filterType: 0,
  attributes: 40,
}

describe('subBoardState（attributes 魔法数，API 文档 §13 第 13 条）', () => {
  it.each(SUBSCRIBED_ATTRIBUTES)('%i 视为已订阅', (attributes) => {
    expect(subBoardState(attributes).subscribed).toBe(true)
  })

  it('不在魔法数表里的一律算未订阅（= 被屏蔽）', () => {
    expect(subBoardState(40).subscribed).toBe(false)
    expect(subBoardState(0).subscribed).toBe(false)
    expect(subBoardState(4655).subscribed).toBe(false)
  })

  it('大于 40 才可改；正好 40 与更小的不给开关', () => {
    expect(subBoardState(FILTERABLE_ATTRIBUTES_MIN + 1).filterable).toBe(true)
    expect(subBoardState(FILTERABLE_ATTRIBUTES_MIN).filterable).toBe(false)
    expect(subBoardState(7).filterable).toBe(false)
  })

  it('真实样本：4654 是已订阅且可改，40 是未订阅且不可改', () => {
    expect(subBoardState(tidSubBoard.attributes)).toEqual({ subscribed: true, filterable: true })
    expect(subBoardState(fidSubBoard.attributes)).toEqual({ subscribed: false, filterable: false })
  })
})

describe('subBoardOptionParam（参数名即操作，且 type 会再反转一次）', () => {
  it('type=1：订阅用 del（从屏蔽表里删掉），屏蔽用 add', () => {
    expect(subBoardOptionParam('subscribe', 1)).toBe('del')
    expect(subBoardOptionParam('block', 1)).toBe('add')
  })

  it('type=0：整个反过来，订阅用 add、屏蔽用 del', () => {
    expect(subBoardOptionParam('subscribe', 0)).toBe('add')
    expect(subBoardOptionParam('block', 0)).toBe('del')
  })

  it('同一 type 下两个动作永远用不同的参数名', () => {
    for (const type of [0, 1] as const) {
      expect(subBoardOptionParam('subscribe', type)).not.toBe(subBoardOptionParam('block', type))
    }
  })
})

describe('nextSubBoardState', () => {
  it('服务端不回新的 attributes，本地按动作直接落状态，可改性不变', () => {
    const state = subBoardState(tidSubBoard.attributes)

    expect(nextSubBoardState(state, 'block')).toEqual({ subscribed: false, filterable: true })
    expect(nextSubBoardState(state, 'subscribe')).toEqual({ subscribed: true, filterable: true })
  })
})

describe('setSubBoardOption', () => {
  it('订阅一个 type=1 的子版块：query 带 del=<filterId>，form 带父 fid/type/info', async () => {
    const { transport, requests } = jsonTransport()

    await setSubBoardOption(createNgaFetcher({ transport }), {
      subBoard: tidSubBoard,
      parentFid: -7,
      action: 'subscribe',
    })

    const url = requests[0]?.url ?? ''
    expect(url).toContain('__lib=user_option')
    expect(url).toContain('__act=set')
    expect(url).toContain('del=12700430')
    // 另一个参数名连出现都不能出现，否则服务端按它理解成反向操作
    expect(url).not.toContain('add=')
    expect(formOf(requests[0])).toMatchObject({
      fid: '-7',
      type: '1',
      info: 'add_to_block_tids',
    })
  })

  it('屏蔽同一个子版块：换成 add=<filterId>', async () => {
    const { transport, requests } = jsonTransport()

    await setSubBoardOption(createNgaFetcher({ transport }), {
      subBoard: tidSubBoard,
      parentFid: -7,
      action: 'block',
    })

    expect(requests[0]?.url).toContain('add=12700430')
    expect(requests[0]?.url).not.toContain('del=')
  })

  it('type=0 的子版块反转：订阅发的是 add，用的是它自己的 id', async () => {
    const { transport, requests } = jsonTransport()

    await setSubBoardOption(createNgaFetcher({ transport }), {
      subBoard: fidSubBoard,
      parentFid: -7,
      action: 'subscribe',
    })

    expect(requests[0]?.url).toContain('add=414')
    expect(requests[0]?.url).not.toContain('del=')
    expect(formOf(requests[0])).toMatchObject({ type: '0' })
  })
})
