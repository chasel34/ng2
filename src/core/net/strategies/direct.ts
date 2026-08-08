import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'
import { runAttempt } from './attempt'

export const DIRECT_STRATEGY_NAME = 'direct'

/**
 * 反封锁链的第一档：直连官方域名发**一次**请求。
 *
 * 只发一次是它与 format-rotation 的全部区别：不轮换、不换账号、不碰缓存。
 * 单测与不需要反封锁的场合（联调、smoke）用它，设备侧的链用 format-rotation。
 *
 * 失败分类决定链要不要往下走：解析失败/HTTP 状态错误 ≈ 被封（可重试），
 * 服务端明确的语义错误（找不到主题之类）不重试，直接抛给调用方。
 */
export function createDirectStrategy(): FetchStrategy {
  return {
    name: DIRECT_STRATEGY_NAME,
    run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome> {
      return runAttempt(request, context, {
        via: DIRECT_STRATEGY_NAME,
        combo: {
          format: request.format ?? 'json',
          host: request.host ?? context.host,
        },
      })
    },
  }
}
