import { describe, expect, it, vi } from 'vitest'

import { createComboCache } from '../combo'
import { parseNgaJson } from '../envelope'
import { NgaError } from '../errors'
import { createNgaFetcher } from '../fetcher'
import type { HttpRequest, HttpResponse, HttpTransport } from '../transport'
import { createFormatRotationStrategy } from './format-rotation'
import {
  TOPIC_CACHE_STRATEGY_NAME,
  createTopicCacheStrategy,
  serializeEnvelope,
  topicCacheKeyOf,
  type TopicCacheKey,
  type TopicCacheReader,
} from './topic-cache'

const utf8 = (text: string) => new TextEncoder().encode(text)

const PAGE_JSON = '{"data":{"__R":{"0":{"content":"第一楼","lou":0}},"__ROWS":1},"time":1}'
const BLOCKED = utf8('<html><body>403 Forbidden</body></html>')

/** 一个只认 tid+page 的假缓存,内容就是序列化后的信封。 */
function fakeStore(entries: Record<string, string> = {}) {
  const read = vi.fn((key: TopicCacheKey) => entries[`${key.tid}/${key.page}`])
  const put = (tid: number, page: number, json: string) => {
    entries[`${tid}/${page}`] = serializeEnvelope(parseNgaJson(json))
  }
  return { store: { read }, read, put }
}

const blockedTransport = (): HttpTransport => () =>
  Promise.resolve({ status: 403, contentType: 'text/html', body: BLOCKED })

const okTransport = (): { transport: HttpTransport; requests: HttpRequest[] } => {
  const requests: HttpRequest[] = []
  const transport: HttpTransport = (request) => {
    requests.push(request)
    return Promise.resolve<HttpResponse>({
      status: 200,
      contentType: 'text/javascript; charset=UTF-8',
      body: utf8(PAGE_JSON),
    })
  }
  return { transport, requests }
}

/** 设备侧那条链的缩影:先打网络,全垮了才轮到缓存(ADR-0002 的顺序)。 */
function chainFetcher(transport: HttpTransport, store: TopicCacheReader) {
  return createNgaFetcher({
    transport,
    comboCache: createComboCache(),
    strategies: [
      createFormatRotationStrategy(),
      createTopicCacheStrategy({ store }),
    ],
  })
}

const readRequest = { path: 'read.php', query: { tid: 45150945, page: 2, v2: 1 } }

describe('topicCacheKeyOf', () => {
  it('整帖阅读的 read.php 认得出 tid 与页码', () => {
    expect(topicCacheKeyOf(readRequest)).toEqual({ tid: 45150945, page: 2 })
  })

  it('没写页码就是第 1 页', () => {
    expect(topicCacheKeyOf({ path: 'read.php', query: { tid: 7 } })).toEqual({ tid: 7, page: 1 })
  })

  it('fav 码不影响缓存身份:带不带码看到的是同一页内容', () => {
    expect(topicCacheKeyOf({ path: 'read.php', query: { tid: 7, page: 1, fav: 'abc' } })).toEqual({
      tid: 7,
      page: 1,
    })
  })

  it('只看该楼 / 只看某人是过滤视图,不缓存', () => {
    expect(topicCacheKeyOf({ path: 'read.php', query: { tid: 7, pid: 99 } })).toBeUndefined()
    expect(topicCacheKeyOf({ path: 'read.php', query: { tid: 7, authorid: 5 } })).toBeUndefined()
  })

  it('别的接口没有缓存', () => {
    expect(topicCacheKeyOf({ path: 'thread.php', query: { fid: 7 } })).toBeUndefined()
    expect(topicCacheKeyOf({ path: 'read.php', query: {} })).toBeUndefined()
  })
})

describe('链上的缓存档', () => {
  it('前面全败、缓存命中时返回缓存数据', async () => {
    const cache = fakeStore()
    cache.put(45150945, 2, PAGE_JSON)

    const result = await chainFetcher(blockedTransport(), cache.store)(readRequest)

    expect(result.via).toBe(TOPIC_CACHE_STRATEGY_NAME)
    expect(result.data).toEqual(parseNgaJson(PAGE_JSON).data)
  })

  it('缓存没命中时不顶替真正的失败原因,错误仍是被封那条', async () => {
    const cache = fakeStore()

    await expect(chainFetcher(blockedTransport(), cache.store)(readRequest)).rejects.toSatisfy(
      (error: NgaError) => error.kind !== 'unavailable' && error.via !== TOPIC_CACHE_STRATEGY_NAME,
    )
    expect(cache.read).toHaveBeenCalledWith({ tid: 45150945, page: 2 })
  })

  it('网络这条路通的时候根本不碰缓存', async () => {
    const cache = fakeStore()
    cache.put(45150945, 2, '{"data":{"__R":{},"__ROWS":0}}')
    const { transport, requests } = okTransport()

    const result = await chainFetcher(transport, cache.store)(readRequest)

    expect(result.via).not.toBe(TOPIC_CACHE_STRATEGY_NAME)
    expect(requests).toHaveLength(1)
    expect(cache.read).not.toHaveBeenCalled()
  })

  it('过滤视图(只看该楼)不读缓存,断网就是断网', async () => {
    const cache = fakeStore()
    cache.put(45150945, 1, PAGE_JSON)

    await expect(
      chainFetcher(blockedTransport(), cache.store)({
        path: 'read.php',
        query: { tid: 45150945, pid: 12345 },
      }),
    ).rejects.toBeInstanceOf(NgaError)
    expect(cache.read).not.toHaveBeenCalled()
  })

  it('缓存里的内容坏了就当没缓存,不把解析异常抛给调用方', async () => {
    const store = { read: () => '这不是 JSON' }

    const error = await chainFetcher(blockedTransport(), store)(readRequest).catch(
      (cause: unknown) => cause as NgaError,
    )
    expect(error.via).not.toBe(TOPIC_CACHE_STRATEGY_NAME)
  })
})

describe('信封往返', () => {
  it('序列化再还原,与原始响应解析出的信封同构', () => {
    const original = parseNgaJson(PAGE_JSON)
    const restored = parseNgaJson(serializeEnvelope(original), TOPIC_CACHE_STRATEGY_NAME)

    expect(restored.root).toEqual(original.root)
    expect(restored.data).toEqual(original.data)
    expect(restored.time).toBe(original.time)
  })

  it('存的是顶层 root:不套壳的响应还原后 data 仍然是它自己', () => {
    // app_api 那种不套 data 壳的响应（envelope.ts：既没 data 也没 error 时顶层即 data）
    const original = parseNgaJson('{"0":{"name":"网事杂谈"}}')
    const restored = parseNgaJson(serializeEnvelope(original))

    expect(restored.data).toEqual(original.data)
  })
})
