/**
 * 用户资料的解析（API 文档 §11.1，`nuke.php?__lib=ucp`）。
 *
 * 两条规矩把这个接口和别的接口区分开：
 *
 * 1. **必须带 Referer**，且要以 base url 开头，否则服务端拒绝（API 文档 §0.3）。
 * 2. **「找不到用户」在假错误白名单里**（core/net 的 `FAKE_ERROR_MESSAGES`），
 *    命中时 `parseNgaJson` 当成功返回、`data` 是 undefined。所以这里必须自己分三种情况：
 *    没有 data（真的没这个人）、有 data 但 `data["0"]` 是空的（服务端抽风）、正常。
 *    两种都得报错，但报的不是同一句话——排障时区分得开「查无此人」和「响应是空的」。
 */

import { toReputation } from '../local'
import { NgaError, isRecord, type NgaFetcher } from '../net'
import { int, nonZero, orderedEntries, str } from './fields'
import { parseAvatarUrl } from './topic-detail'
import type { AdminForum, ReputationEntry, UserProfile, UserStatus } from './types'

/** `verified`/`yz` 的这个取值表示账号被 nuke（API 文档 §11.1）。 */
const NUKED_VERIFIED = -1

/** 资料接口的 Referer，必须以 base url 开头（API 文档 §11.1）。 */
export const UCP_REFERER_PATH = 'nuke.php?func=ucp'

/** 服务端在没有 IP 记录时给的占位，不是真的属地。 */
const NO_IP_RECORD = '尚无记录'

/**
 * `adminForums`：`{ "<fid>": "版面名" }`，fid 可以是负数（`-2` 这种是合集/特殊版面）。
 * 实测只有真的担任职务的账号才有这个键。
 */
function parseAdminForums(raw: unknown): AdminForum[] {
  const forums: AdminForum[] = []
  for (const [key, value] of orderedEntries(raw)) {
    const fid = Number(key)
    if (!Number.isFinite(fid) || typeof value !== 'string' || value.trim() === '') continue
    forums.push({ fid, name: value.trim() })
  }
  return forums
}

/**
 * `reputation`：各版声望。抓包样本里没见过这个键（只有攒过声望的账号才有），
 * 所以两种可能的形状都收：`{ "<fid>": 42 }` 与 `{ "<fid>": { name, value } }`。
 * 名字解不出来就退回 `版面 <fid>`——条形图那行总得有个左边的标签。
 */
function parseReputations(raw: unknown): ReputationEntry[] {
  const entries: ReputationEntry[] = []
  for (const [key, value] of orderedEntries(raw)) {
    const fid = Number(key)
    if (!Number.isFinite(fid)) continue

    if (isRecord(value)) {
      const amount = int(value, 'value') ?? int(value, '1') ?? int(value, '0')
      if (amount === undefined) continue
      const name = str(value, 'name') ?? str(value, '0')
      entries.push({ fid, name: name ?? `版面 ${fid}`, value: amount })
      continue
    }

    const amount = int({ value }, 'value')
    if (amount === undefined) continue
    entries.push({ fid, name: `版面 ${fid}`, value: amount })
  }
  return entries
}

/**
 * 账号状态（设计稿基础信息卡的「状态」一格）。
 *
 * 被 nuke 压过禁言：一个已经被封的号还标「禁言中」没有意义。
 * `muteTime` 是禁言到期的秒级时间戳，0 表示没禁言；过了期也不再算禁言，
 * 所以要拿 `now` 比一比而不是只判非 0。
 */
function parseStatus(raw: Record<string, unknown>, nowSeconds: number): {
  status: UserStatus
  mutedUntil?: number
} {
  const verified = int(raw, 'verified') ?? int(raw, 'yz')
  if (verified === NUKED_VERIFIED) return { status: 'nuked' }

  const mutedUntil = nonZero(int(raw, 'muteTime') ?? int(raw, 'mute_time'))
  if (mutedUntil !== undefined && mutedUntil > nowSeconds) {
    return { status: 'muted', mutedUntil }
  }
  return { status: 'active' }
}

export interface ParseUserProfileOptions {
  /** 判定禁言是否还在有效期内的基准时刻，秒级 unix 时间戳 */
  readonly nowSeconds?: number
}

/**
 * 解一份用户资料。传的是响应的 `data`。
 *
 * 解不出用户时返回 undefined（而不是抛）：调用方要按「找不到用户 / 响应是空的」
 * 分别措辞，判空的活儿留给它。
 */
export function parseUserProfile(
  data: unknown,
  options: ParseUserProfileOptions = {},
): UserProfile | undefined {
  const raw = isRecord(data) ? data['0'] : undefined
  if (!isRecord(raw)) return undefined

  const uid = int(raw, 'uid')
  if (uid === undefined) return undefined

  const avatarUrl = parseAvatarUrl(raw.avatar)
  const group = str(raw, 'group')
  const email = str(raw, 'email')
  const phone = str(raw, 'phone')
  const registeredAt = nonZero(int(raw, 'regdate'))
  const ipLoc = str(raw, 'ipLoc')
  const signature = str(raw, 'sign') ?? str(raw, 'signature')
  const nowSeconds = options.nowSeconds ?? Math.floor(Date.now() / 1000)
  const { status, mutedUntil } = parseStatus(raw, nowSeconds)

  return {
    uid,
    name: str(raw, 'username') ?? `UID ${uid}`,
    ...(avatarUrl === undefined ? {} : { avatarUrl }),
    ...(group === undefined ? {} : { group }),
    ...(email === undefined ? {} : { email }),
    ...(phone === undefined ? {} : { phone }),
    postCount: int(raw, 'posts') ?? int(raw, 'postnum') ?? 0,
    money: int(raw, 'money') ?? 0,
    reputation: toReputation(int(raw, 'rvrc') ?? int(raw, 'fame') ?? 0),
    ...(registeredAt === undefined ? {} : { registeredAt }),
    // 「尚无记录」是服务端的占位文案而不是属地，别把它当成一个地名显示出去
    ...(ipLoc === undefined || ipLoc === NO_IP_RECORD ? {} : { ipLocation: ipLoc }),
    status,
    ...(mutedUntil === undefined ? {} : { mutedUntil }),
    ...(signature === undefined ? {} : { signature }),
    adminForums: parseAdminForums(raw.adminForums),
    reputations: parseReputations(raw.reputation),
  }
}

export interface FetchUserProfileOptions {
  readonly uid: number
  readonly signal?: AbortSignal
}

/**
 * 拉一份用户资料（`POST nuke.php?__lib=ucp&__act=get`）。
 *
 * `refererPath` 而不是写死的完整 URL：反封锁链会换域名，Referer 得跟着当前 host 走
 * （要求只是「以 base url 开头」）。
 */
export async function fetchUserProfile(
  fetchNga: NgaFetcher,
  options: FetchUserProfileOptions,
): Promise<UserProfile> {
  const { uid, signal } = options

  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'ucp', __act: 'get', uid },
    refererPath: UCP_REFERER_PATH,
    ...(signal === undefined ? {} : { signal }),
  })

  // 「找不到用户」在假错误白名单里，走不到 NgaError，只能靠 data 为空认出来
  if (!isRecord(result.data)) {
    throw new NgaError({
      kind: 'server',
      message: result.fakeError?.message ?? '找不到用户',
      via: result.via,
    })
  }

  const profile = parseUserProfile(result.data)
  if (profile === undefined) {
    // data 在、user 不在：服务端偶尔这样抽风，和「查无此人」不是一回事
    throw new NgaError({ kind: 'parse', message: '资料响应里没有用户', via: result.via })
  }
  return profile
}

export interface FetchUserAvatarOptions {
  readonly uid: number
  readonly signal?: AbortSignal
}

/**
 * 头像补充查询（API 文档 §11.2）。资料接口的 `avatar` 是空串时才用得上，
 * 拿不到就返回 undefined——UI 那边还有首字占位兜底，不该为一张头像报错。
 */
export async function fetchUserAvatar(
  fetchNga: NgaFetcher,
  options: FetchUserAvatarOptions,
): Promise<string | undefined> {
  const { uid, signal } = options

  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'ucp', __act: 'get_avatar', uid },
    refererPath: UCP_REFERER_PATH,
    ...(signal === undefined ? {} : { signal }),
  })

  // 这个接口的 data["0"] 是 URL 字符串本身，不是对象
  return isRecord(result.data) ? parseAvatarUrl(result.data['0']) : undefined
}
