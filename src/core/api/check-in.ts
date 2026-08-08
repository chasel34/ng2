/**
 * 每日签到(CONTEXT.md「签到」,API 文档 §11.4):
 *
 * ```
 * POST nuke.php?__lib=check_in&__act=check_in     # 无额外参数
 * ```
 *
 * 「今天已经签到」在**假错误白名单**里(core/net 的 `FAKE_ERROR_MESSAGES`),
 * 命中时 `parseNgaJson` 已经当成功返回了,这里只是把它认出来,好让 UI
 * 说「今天已经签过」而不是「签到成功」——两句话对用户不是一回事。
 *
 * 去重在 core/local/check-in.ts:今天签过就压根不发这个请求。
 */

import { isRecord, type NgaFetcher } from '../net'
import { str } from './fields'

/** 服务端说「今天已经签到」时的判据。 */
const ALREADY_MESSAGE = '今天已经签到'

export interface CheckInResult {
  /** 服务端说今天已经签过了(不是失败,只是这次没算新的一签) */
  readonly alreadyCheckedIn: boolean
  /** 服务端原话,拿去 toast;拿不到就由 UI 用默认文案 */
  readonly message?: string
}

/**
 * 签一次到。真失败(未登录、被封)由 envelope 抛 `kind: 'server'`,能返回就是成功。
 */
export async function checkIn(fetchNga: NgaFetcher, signal?: AbortSignal): Promise<CheckInResult> {
  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'check_in', __act: 'check_in' },
    ...(signal === undefined ? {} : { signal }),
  })

  const fake = result.fakeError?.message
  // 成功时的文案在 data["0"](和别的写操作一样是一句「操作成功」类的话)
  const ok = isRecord(result.data) ? str(result.data, '0') : undefined
  const message = fake ?? ok

  return {
    alreadyCheckedIn: fake !== undefined && fake.includes(ALREADY_MESSAGE),
    ...(message === undefined ? {} : { message }),
  }
}
