import { interfaceKeyOf, type FetchCombo } from '../combo'
import { NgaError } from '../errors'
import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'
import { parseReadPageHtml } from '../web/read-html'
import { runAttempt } from './attempt'

export const WEB_FALLBACK_STRATEGY_NAME = 'web-fallback'

/** 只有 `read.php` 有网页反解器（API 文档 §0.8：这一档是 read.php 专用）。 */
const SUPPORTED_PATH = 'read.php'

/**
 * Web 反解档位（API 文档 §0.8 的四档）。
 *
 * - `disabled` 关掉，链上当这一档不存在
 * - `secondary` 默认：排在换账号之后，原生全垮了才反解
 * - `primary` `read.php` 优先反解，反解不出来再退回原生
 * - `only` 只走反解，原生一次都不打（排查「是不是被封」时用）
 */
export type WebFallbackMode = 'disabled' | 'secondary' | 'primary' | 'only'

export const DEFAULT_WEB_FALLBACK_MODE: WebFallbackMode = 'secondary'

export interface WebFallbackOptions {
  /**
   * 这一条在链上占的位置。**同一档位要在链上放两次**：
   * `primary` 那条排在最前（`primary`/`only` 时才真跑），
   * `secondary` 那条排在换账号之后（`secondary` 时才真跑）。
   * 档位是用户设置，链的顺序是建 fetcher 时定死的，只能这么接。
   */
  readonly placement: 'primary' | 'secondary'
  /**
   * 档位读取口，每次请求现取（设置页改了下一个请求即生效）。
   * 注入而不是 import store：core 层零 RN 依赖。
   */
  readonly getMode?: () => WebFallbackMode
}

function unavailable(message: string): StrategyOutcome {
  return {
    ok: false,
    error: new NgaError({
      kind: 'unavailable',
      message,
      via: WEB_FALLBACK_STRATEGY_NAME,
    }),
  }
}

/** 这个位置在当前档位下要不要跑。 */
function activeAt(placement: WebFallbackOptions['placement'], mode: WebFallbackMode): boolean {
  return placement === 'primary' ? mode === 'primary' || mode === 'only' : mode === 'secondary'
}

/**
 * 反封锁链的 Web 反解档（ADR-0002 / API 文档 §0.8）。
 *
 * 拿掉格式参数就是给浏览器看的那张网页，数据仍以内联 JS 的形态躺在里面
 * （`core/net/web/read-html` 负责把它们抠回 `__output=8` 的形状）。所以这一档
 * 与前面几档的差别只是**换一种响应格式**，请求本身照旧走 `runAttempt`——
 * 拼 URL、附认证、GBK 解码、失败分类全是同一套。
 *
 * 只试一次、不枚举组合：前面 format-rotation 已经把域名空间跑完了，这一档变的是格式。
 */
export function createWebFallbackStrategy(options: WebFallbackOptions): FetchStrategy {
  const { placement } = options
  const getMode = options.getMode ?? (() => DEFAULT_WEB_FALLBACK_MODE)

  return {
    name: WEB_FALLBACK_STRATEGY_NAME,
    async run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome> {
      // 别的接口没有反解器，什么档位都轮不到这一档——包括 `only`：
      // 那个档位说的是「read.php 只走反解」，不是「整个 app 断网」
      if (!request.path.startsWith(SUPPORTED_PATH)) {
        return unavailable(`Web 反解只支持 ${SUPPORTED_PATH}，这条是 ${request.path}`)
      }

      const mode = getMode()
      if (!activeAt(placement, mode)) {
        return unavailable(`Web 反解档位是 ${mode}，${placement} 位置不启用`)
      }

      const combo: FetchCombo = {
        format: 'html',
        // 域名沿用上一档试通的那个（没有就用默认）：这一档换的是格式，不是域名
        host: request.host ?? context.comboCache?.get(interfaceKeyOf(request))?.host ?? context.host,
      }
      const outcome = await runAttempt(request, context, {
        via: WEB_FALLBACK_STRATEGY_NAME,
        combo,
        parse: (text) => parseReadPageHtml(text, WEB_FALLBACK_STRATEGY_NAME),
        ...(context.renewTransport === undefined ? {} : { transport: context.renewTransport() }),
      })

      if (outcome.ok || mode !== 'only' || !outcome.error.retryable) return outcome
      // `only` 档说好了不碰原生接口，所以这一档失败就是终点——
      // 标成不可重试，`runStrategyChain` 会当场收手而不是接着往下试
      return {
        ok: false,
        error: new NgaError({
          kind: outcome.error.kind,
          message: outcome.error.message,
          via: WEB_FALLBACK_STRATEGY_NAME,
          cause: outcome.error,
          retryable: false,
          ...(outcome.error.status === undefined ? {} : { status: outcome.error.status }),
        }),
      }
    },
  }
}
