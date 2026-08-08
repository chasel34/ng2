import { parseNgaJson, type NgaEnvelope } from '../envelope'
import { NgaError } from '../errors'
import { isGbkParam, type QueryValue } from '../query'
import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'

export const TOPIC_CACHE_STRATEGY_NAME = 'topic-cache'

/** 只有 `read.php` 有缓存（缓存的粒度就是「主题的一页」）。 */
const SUPPORTED_PATH = 'read.php'

/** 缓存一页的定位：主题 + 页码。 */
export interface TopicCacheKey {
  readonly tid: number
  /** 从 1 起 */
  readonly page: number
}

/**
 * 缓存档要的最小存储口。设备侧接 SQLite（`src/store/topic-cache.ts`），
 * 单测接一个 Map——core 层零 RN 依赖，存储一律注入。
 *
 * 返回的是**序列化后的信封**（`serializeEnvelope` 的产物），不是解析结果：
 * 还原走 `parseNgaJson`，与在线那条路完全同一段代码，信封天然同构。
 */
export interface TopicCacheReader {
  read(key: TopicCacheKey): string | undefined | Promise<string | undefined>
}

/**
 * 把一次响应存成可以还原的文本。
 *
 * 存顶层 `root` 而不是 `data`：`data` 是 `parseNgaJson` 按「有没有 data/error 键」
 * 推出来的，只存它的话还原时推不回同一个结果（不套壳的接口会被当成套壳的）。
 * Web 反解档（19 票）产出的信封 root 同样是普通对象，两条路存出来的东西可以互换。
 */
export function serializeEnvelope(envelope: NgaEnvelope): string {
  return JSON.stringify(envelope.root)
}

function numberOf(value: QueryValue): number | undefined {
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined
  const text = isGbkParam(value) ? value.value : typeof value === 'string' ? value : undefined
  if (text === undefined || text.trim() === '') return undefined
  const parsed = Number(text)
  return Number.isFinite(parsed) ? parsed : undefined
}

/**
 * 这条请求缓存得起来吗？缓存得起来就给出它的 key。
 *
 * 只认整帖阅读：带 `pid`（只看该楼）或 `authorid`（只看某人）的请求是**过滤视图**，
 * 服务端会重排楼层与页码，按 tid+page 存下来会污染整帖那一份。fav 码不影响内容
 * （它是访问凭据，不是筛选条件），所以带不带 fav 命中同一份缓存。
 */
export function topicCacheKeyOf(request: NgaRequest): TopicCacheKey | undefined {
  if (!request.path.startsWith(SUPPORTED_PATH)) return undefined
  const query = request.query ?? {}
  if (query.pid !== undefined && query.pid !== null) return undefined
  if (query.authorid !== undefined && query.authorid !== null) return undefined

  const tid = numberOf(query.tid)
  if (tid === undefined || tid <= 0) return undefined
  const page = numberOf(query.page) ?? 1
  return { tid, page: page > 0 ? Math.trunc(page) : 1 }
}

function unavailable(message: string): StrategyOutcome {
  return {
    ok: false,
    error: new NgaError({
      kind: 'unavailable',
      message,
      via: TOPIC_CACHE_STRATEGY_NAME,
    }),
  }
}

export interface TopicCacheStrategyOptions {
  readonly store: TopicCacheReader
}

/**
 * 反封锁链的帖子缓存档（ADR-0002：Web 反解之后、网页兜底页之前）。
 *
 * 前面几档都在打网络，这一档一个请求都不发——断网、被封、账号全挂的时候，
 * 本机存着的那一页就是用户还能看到的东西。缓存里没有就报 `unavailable`
 * 让链继续（`runStrategyChain` 不会拿它盖掉前面更实质的错误）。
 */
export function createTopicCacheStrategy(options: TopicCacheStrategyOptions): FetchStrategy {
  return {
    name: TOPIC_CACHE_STRATEGY_NAME,
    async run(request: NgaRequest, _context: FetchContext): Promise<StrategyOutcome> {
      const key = topicCacheKeyOf(request)
      if (key === undefined) {
        return unavailable(`帖子缓存只认整帖阅读的 ${SUPPORTED_PATH}，这条是 ${request.path}`)
      }

      let payload: string | undefined
      try {
        payload = await options.store.read(key)
      } catch (cause) {
        // 本地库出问题不该顶替「这一页被封了」当最终错误，仍按「这一档不适用」处理
        return unavailable(
          `读缓存失败：${cause instanceof Error ? cause.message : String(cause)}`,
        )
      }
      if (payload === undefined) {
        return unavailable(`缓存里没有 tid=${key.tid} 的第 ${key.page} 页`)
      }

      try {
        const envelope = parseNgaJson(payload, TOPIC_CACHE_STRATEGY_NAME)
        return { ok: true, result: { ...envelope, via: TOPIC_CACHE_STRATEGY_NAME } }
      } catch (cause) {
        // 存进去的东西自己解不出来（旧版本写的、写坏了）：当作没缓存
        return unavailable(
          `缓存内容无法还原：${cause instanceof Error ? cause.message : String(cause)}`,
        )
      }
    },
  }
}
