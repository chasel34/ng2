import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import {
  expectedRecommendDelta,
  nextRecommendState,
  postRecommend,
  recommendStateOf,
  type RecommendAction,
  type RecommendState,
} from './topic-recommend'

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

describe('切换式状态迁移（CONTEXT.md 外的 NGA 特色：赞踩没有幂等）', () => {
  const cases: readonly [RecommendState, RecommendAction, RecommendState, number][] = [
    // [当前状态, 动作, 下一状态, 预期 delta]
    ['none', 'like', 'liked', 1],
    ['liked', 'like', 'none', -1], // 再赞一次 = 取消
    ['disliked', 'like', 'liked', 2], // 踩翻成赞：撤一踩再加一赞
    ['none', 'dislike', 'disliked', -1],
    ['disliked', 'dislike', 'none', 1], // 再踩一次 = 取消
    ['liked', 'dislike', 'disliked', -2], // 赞翻成踩
  ]

  it.each(cases)('%s + %s → %s（delta %i）', (current, action, next, delta) => {
    expect(nextRecommendState(current, action)).toBe(next)
    expect(expectedRecommendDelta(current, action)).toBe(delta)
  })

  it('预测迁移与服务端 delta 语义互相一致：按预期 delta 反推回的就是预测状态', () => {
    for (const [current, action, next, delta] of cases) {
      expect(recommendStateOf(action, delta)).toBe(next)
      // 顺带钉死 current 参与运算的方式没有跑偏
      expect(expectedRecommendDelta(current, action)).toBe(delta)
    }
  })
})

describe('recommendStateOf（服务端 delta 语义，API 文档 §6）', () => {
  it('点赞且 delta>0 → 已赞', () => {
    expect(recommendStateOf('like', 1)).toBe('liked')
    expect(recommendStateOf('like', 2)).toBe('liked')
  })

  it('点踩且 delta<0 → 已踩', () => {
    expect(recommendStateOf('dislike', -1)).toBe('disliked')
    expect(recommendStateOf('dislike', -2)).toBe('disliked')
  })

  it('其余组合都是取消：赞收到负 delta、踩收到正 delta、delta 为 0', () => {
    expect(recommendStateOf('like', -1)).toBe('none')
    expect(recommendStateOf('like', 0)).toBe('none')
    expect(recommendStateOf('dislike', 1)).toBe('none')
    expect(recommendStateOf('dislike', 0)).toBe('none')
  })
})

describe('postRecommend', () => {
  it('打 nuke.php topic_recommend add，点赞 value=1', async () => {
    const { transport, requests } = jsonTransport('{"data":{"1":1},"time":1}')
    const result = await postRecommend(createNgaFetcher({ transport }), {
      tid: 45150945,
      pid: 123456,
      action: 'like',
    })

    expect(requests[0]?.url).toContain('/nuke.php?')
    expect(requests[0]?.url).toContain('__lib=topic_recommend')
    expect(requests[0]?.url).toContain('__act=add')
    expect(requests[0]?.url).toContain('value=1')
    expect(requests[0]?.url).toContain('tid=45150945')
    expect(requests[0]?.url).toContain('pid=123456')
    expect(result).toEqual({ delta: 1, state: 'liked' })
  })

  it('点踩 value=-1，负 delta 判成已踩', async () => {
    const { transport, requests } = jsonTransport('{"data":{"1":-1},"time":1}')
    const result = await postRecommend(createNgaFetcher({ transport }), {
      tid: 45150945,
      pid: 123456,
      action: 'dislike',
    })

    expect(requests[0]?.url).toContain('value=-1')
    expect(result).toEqual({ delta: -1, state: 'disliked' })
  })

  it('主楼 pid=0 必须真的出现在参数里，不能被空值剔除规则吃掉', async () => {
    const { transport, requests } = jsonTransport('{"data":{"1":1},"time":1}')
    await postRecommend(createNgaFetcher({ transport }), {
      tid: 45150945,
      pid: 0,
      action: 'like',
    })

    expect(requests[0]?.url).toContain('pid=0')
  })

  it('取消点赞：delta 为负时状态回到 none', async () => {
    const { transport } = jsonTransport('{"data":{"1":-1},"time":1}')
    const result = await postRecommend(createNgaFetcher({ transport }), {
      tid: 45150945,
      pid: 123456,
      action: 'like',
    })
    expect(result).toEqual({ delta: -1, state: 'none' })
  })

  it('delta 也可能在 data["0"]，字符串数字一并收下', async () => {
    const { transport } = jsonTransport('{"data":{"0":"2"},"time":1}')
    const result = await postRecommend(createNgaFetcher({ transport }), {
      tid: 45150945,
      pid: 123456,
      action: 'like',
    })
    expect(result).toEqual({ delta: 2, state: 'liked' })
  })

  it('响应里挖不出 delta 时报解析错', async () => {
    const { transport } = jsonTransport('{"data":{"0":"操作成功"},"time":1}')
    await expect(
      postRecommend(createNgaFetcher({ transport }), { tid: 1, pid: 2, action: 'like' }),
    ).rejects.toThrow(/分数增量/)
  })

  it('服务端语义错误原样抛出（envelope 兜底）', async () => {
    const { transport } = jsonTransport('{"error":{"0":"你操作的太快了"},"time":1}')
    await expect(
      postRecommend(createNgaFetcher({ transport }), { tid: 1, pid: 2, action: 'like' }),
    ).rejects.toThrow('你操作的太快了')
  })
})
