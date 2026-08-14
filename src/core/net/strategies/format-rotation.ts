import {
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_ROTATION_FORMATS,
  enumerateCombos,
  interfaceKeyOf,
  type FetchCombo,
} from '../combo'
import { NGA_HOSTS, type ResponseFormat } from '../constants'
import { NgaError, isAuthLevelServerError } from '../errors'
import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'
import { runAttempt } from './attempt'

export const FORMAT_ROTATION_STRATEGY_NAME = 'format-rotation'

export interface FormatRotationOptions {
  /** 参与轮换的格式档位，默认 JSON 家族三档（见 combo.ts 为什么不含 XML） */
  readonly formats?: readonly ResponseFormat[]
  /** 参与轮换的域名，默认全部官方域名（API 文档 §0.1） */
  readonly hosts?: readonly string[]
  /** 组合数上限，默认 6 */
  readonly maxAttempts?: number
}

/**
 * 反封锁链前半段的主力：**格式参数 × 域名**的组合枚举（ADR-0002 / API 文档 §0.8）。
 *
 * - 只有解析错误 / HTTP 状态错误 / 网络错误才换下一个组合；服务端语义错误
 *   （权限不足、找不到主题）说明这个组合根本没被封，立刻交回链上抛给调用方。
 * - 成功（含「成功地拿到语义错误」）的组合按接口 key 记进缓存，下次这个接口优先用它开局。
 * - 每次重试前重建 HTTP client（MNGA 的做法：连接可能已经被中间设备盯上）。
 */
export function createFormatRotationStrategy(options: FormatRotationOptions = {}): FetchStrategy {
  const formats = options.formats ?? DEFAULT_ROTATION_FORMATS
  const hosts = options.hosts ?? NGA_HOSTS
  const maxAttempts = options.maxAttempts ?? DEFAULT_MAX_ATTEMPTS

  return {
    name: FORMAT_ROTATION_STRATEGY_NAME,
    async run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome> {
      const key = interfaceKeyOf(request)
      const cache = context.comboCache
      // 调用方点名了格式或域名就把那个组合排在轮换前面，但不独占——
      // 它照样可能被封，被封了还是要换。
      const requested: FetchCombo | undefined =
        request.format === undefined && request.host === undefined
          ? undefined
          : {
              format: request.format ?? formats[0] ?? 'json',
              host: request.host ?? context.host,
            }
      const preferred = cache?.get(key)
      const combos = enumerateCombos({
        formats,
        // 默认域名（设置页可改）永远排在官方域名表前面
        hosts: [context.host, ...hosts],
        maxAttempts,
        ...(requested === undefined ? {} : { requested }),
        ...(preferred === undefined ? {} : { preferred }),
      })

      let transport = context.transport
      let lastError: NgaError | undefined
      for (let index = 0; index < combos.length; index += 1) {
        const combo = combos[index]!
        // 重试前重建 HTTP client（第一次直接用链上现成的那个）
        if (index > 0 && context.renewTransport !== undefined) transport = context.renewTransport()

        const outcome = await runAttempt(request, context, {
          via: FORMAT_ROTATION_STRATEGY_NAME,
          combo,
          transport,
        })
        if (outcome.ok) {
          cache?.remember(key, combo)
          return outcome
        }
        // 缓存里那个组合当场失手就先摘掉：并发的同接口请求不该再从它开局，
        // 而这一轮后面的组合成功了自然会把新的写回去
        if (combo.format === preferred?.format && combo.host === preferred.host) cache?.forget(key)
        lastError = outcome.error
        // 游客态的「未登录」：换域名救不了（哪个域名都没有 cookie），别白跑一整轮组合。
        // 但错误本身仍然是可重试的，链上后面的网页兜底/帖子缓存还该拿到机会。
        if (context.credentials === null && isAuthLevelServerError(outcome.error.message)) {
          return outcome
        }
        if (!outcome.error.retryable) {
          // 能解析出服务端语义错误 = 这个组合是通的，值得记住
          if (outcome.error.kind === 'server') cache?.remember(key, combo)
          return outcome
        }
      }

      // 全组合失败：缓存里那个也不灵了，清掉免得下次还从它开局
      cache?.forget(key)
      return {
        ok: false,
        error:
          lastError ??
          new NgaError({
            kind: 'unavailable',
            message: '没有可尝试的格式 × 域名组合',
            via: FORMAT_ROTATION_STRATEGY_NAME,
          }),
      }
    },
  }
}
