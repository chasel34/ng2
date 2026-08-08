/**
 * 点赞 / 点踩（API 文档 §6，`nuke.php?__lib=topic_recommend`）。
 *
 * NGA 的赞踩是**切换式**：同一动作按第二次是取消，先踩再赞会把踩直接翻成赞。
 * 服务端不回「现在是什么状态」，只回分数增量 delta——最终状态按「动作 + delta 符号」
 * 判（`recommendStateOf`）。客户端乐观更新用的预测迁移（`nextRecommendState` /
 * `expectedRecommendDelta`）与这套 delta 语义保持一致，单测互相锁死。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { int } from './fields'

export type RecommendAction = 'like' | 'dislike'

/** 当前用户对某楼层的赞踩状态。服务端不下发初值，会话内从 `none` 起算。 */
export type RecommendState = 'none' | 'liked' | 'disliked'

/**
 * 一个楼层的本地赞踩标记：状态 + 相对服务端 `score` 的累计增量。
 * UI 显示的赞数 = `floor.score + scoreDelta`。
 */
export interface RecommendMark {
  readonly state: RecommendState
  readonly scoreDelta: number
}

/** 一次赞踩请求的结果：服务端 delta 与据它判出的最终状态。 */
export interface RecommendResult {
  readonly state: RecommendState
  readonly delta: number
}

/** 状态对分数的贡献：已赞 +1、已踩 -1、没表态 0。迁移 delta 都由它导出。 */
const scoreOf = (state: RecommendState): number =>
  state === 'liked' ? 1 : state === 'disliked' ? -1 : 0

/** 切换式状态迁移：同一动作再按一次是取消，反向动作直接翻面。 */
export function nextRecommendState(
  current: RecommendState,
  action: RecommendAction,
): RecommendState {
  if (action === 'like') return current === 'liked' ? 'none' : 'liked'
  return current === 'disliked' ? 'none' : 'disliked'
}

/**
 * 预测这次动作会让分数变多少（乐观更新用）。
 * 从踩翻成赞是 +2（撤一踩再加一赞），与服务端实际返回的 delta 一致。
 */
export function expectedRecommendDelta(current: RecommendState, action: RecommendAction): number {
  return scoreOf(nextRecommendState(current, action)) - scoreOf(current)
}

/**
 * 按服务端 delta 判最终状态（API 文档 §6）：
 * 点赞且 delta>0 → 已赞；点踩且 delta<0 → 已踩；其余都是取消/无表态。
 */
export function recommendStateOf(action: RecommendAction, delta: number): RecommendState {
  if (action === 'like' && delta > 0) return 'liked'
  if (action === 'dislike' && delta < 0) return 'disliked'
  return 'none'
}

export interface PostRecommendOptions {
  readonly tid: number
  /** 楼层 pid；**主楼传 0**（API 文档 §6），0 也必须真的出现在参数里 */
  readonly pid: number
  readonly action: RecommendAction
  readonly signal?: AbortSignal
}

/**
 * 发一次赞/踩。语义错误（未登录、操作太快）由 envelope 抛 `kind: 'server'`，
 * 能返回就是服务端已经记上了。
 */
export async function postRecommend(
  fetchNga: NgaFetcher,
  options: PostRecommendOptions,
): Promise<RecommendResult> {
  const { tid, pid, action, signal } = options
  const result = await fetchNga({
    path: 'nuke.php',
    query: {
      __lib: 'topic_recommend',
      __act: 'add',
      value: action === 'like' ? 1 : -1,
      tid,
      pid,
    },
    ...(signal === undefined ? {} : { signal }),
  })

  const data = isRecord(result.data) ? result.data : {}
  // delta 在 data["1"] 或 data["0"]（API 文档 §6）；data["0"] 偶尔是文案，解不出数就看下一个
  const delta = int(data, '1') ?? int(data, '0')
  if (delta === undefined) {
    throw new NgaError({ kind: 'parse', message: '赞踩响应里没有分数增量', via: result.via })
  }
  return { delta, state: recommendStateOf(action, delta) }
}
