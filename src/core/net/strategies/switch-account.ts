import type { NgaCredentials } from '../auth'
import { DEFAULT_ROTATION_FORMATS, interfaceKeyOf, type FetchCombo } from '../combo'
import { NgaError } from '../errors'
import type { FetchContext, FetchStrategy, NgaRequest, StrategyOutcome } from '../types'
import { runAttempt } from './attempt'

export const SWITCH_ACCOUNT_STRATEGY_NAME = 'switch-account'

export interface SwitchAccountOptions {
  /**
   * 已登录账号的凭证表，顺序即账号管理页的顺序。
   *
   * 注入而不是 import store：core 层零 RN 依赖，账号存在 SecureStore 里，
   * 拿到这一层的只能是纯数据（src/store/accounts.ts 提供实现）。
   */
  readonly listCredentials: () => readonly NgaCredentials[]
}

/**
 * 当前凭证之后的下一个账号（循环取）。
 * 只有一个账号（或一个都没有）时返回 null——没得换，这一档就不该启用。
 * 当前凭证不在表里（刚退出登录之类）时从头一个开始。
 */
export function nextCredentialsAfter(
  accounts: readonly NgaCredentials[],
  current: NgaCredentials | null,
): NgaCredentials | null {
  if (accounts.length < 2) return null
  const index = current === null ? -1 : accounts.findIndex((item) => item.uid === current.uid)
  return accounts[(index + 1) % accounts.length] ?? null
}

/**
 * 反封锁链的换账号重试档（ADR-0002；Android v4 的对策）：
 * 取下一个已登录账号的 cookie **只试一次**，成了就成了，不成交给链上后面的兜底。
 *
 * 只在多账号时启用：单账号换来换去还是同一个 cookie，白等一次往返。
 * 用哪个组合不再枚举——前一档 format-rotation 已经把组合空间跑完了，
 * 这一档变的是**身份**，所以沿用缓存里的组合（没有就用默认档）。
 */
export function createSwitchAccountStrategy(options: SwitchAccountOptions): FetchStrategy {
  return {
    name: SWITCH_ACCOUNT_STRATEGY_NAME,
    async run(request: NgaRequest, context: FetchContext): Promise<StrategyOutcome> {
      const via = SWITCH_ACCOUNT_STRATEGY_NAME
      const current =
        request.credentials === undefined ? context.credentials : request.credentials
      const next = nextCredentialsAfter(options.listCredentials(), current)
      if (next === null) {
        return {
          ok: false,
          error: new NgaError({
            kind: 'unavailable',
            message: '只有一个已登录账号，没有可换的',
            via,
          }),
        }
      }

      const cached = context.comboCache?.get(interfaceKeyOf(request))
      const combo: FetchCombo = cached ?? {
        format: request.format ?? DEFAULT_ROTATION_FORMATS[0] ?? 'json',
        host: request.host ?? context.host,
      }
      return runAttempt(request, context, { via, combo, credentials: next })
    },
  }
}
