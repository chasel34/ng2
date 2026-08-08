/**
 * 签到的本地去重(CONTEXT.md「签到」:每日一次,客户端按 UTC+8 日期本地去重)。
 *
 * 服务端不给「今天签没签过」的查询接口,只在重复签到时回一句「今天已经签到」
 * (还是**假错误**白名单里的,当成功处理)。所以要不要发这次请求由本地记账决定:
 * 每个账号记一条「最后签到日」,日期一致就不再打接口。
 *
 * 日界线按 **UTC+8** 而不是设备时区:NGA 的一天是北京时间的一天,
 * 用户人在别的时区时,按设备时区算会在午夜前后多签或少签一次。
 */

/** 北京时间相对 UTC 的偏移(NGA 全年不调时,固定 +8)。 */
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000

/** 某账号最后一次签到的日期,`uid → YYYY-MM-DD`(UTC+8)。 */
export type CheckInDays = Readonly<Record<string, string>>

export const EMPTY_CHECK_IN_DAYS: CheckInDays = {}

/** 某个时刻落在 UTC+8 的哪一天,形如 `2026-08-08`。 */
export function beijingDayKey(nowMs: number): string {
  // 先把时间轴整体挪 +8 小时,再按 UTC 取年月日——等价于换算到北京时间的当天
  return new Date(nowMs + BEIJING_OFFSET_MS).toISOString().slice(0, 10)
}

/** 这个账号今天(UTC+8)签过了吗。 */
export function isCheckedInOn(days: CheckInDays, uid: string, nowMs: number): boolean {
  return days[uid] === beijingDayKey(nowMs)
}

/** 记一次签到成功。同一账号只留最后一天,不攒历史。 */
export function markCheckedIn(days: CheckInDays, uid: string, nowMs: number): CheckInDays {
  return { ...days, [uid]: beijingDayKey(nowMs) }
}

const DAY_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/

/**
 * 从持久化的 JSON 还原。坏数据一律当「没签过」——
 * 最坏结果只是多发一次签到请求,而服务端本来就幂等(重复签到回假错误)。
 */
export function parseCheckInDays(raw: string | null | undefined): CheckInDays {
  if (raw === null || raw === undefined || raw === '') return EMPTY_CHECK_IN_DAYS
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return EMPTY_CHECK_IN_DAYS
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return EMPTY_CHECK_IN_DAYS
  }
  const days: Record<string, string> = {}
  for (const [uid, day] of Object.entries(parsed)) {
    if (typeof day === 'string' && DAY_KEY_PATTERN.test(day)) days[uid] = day
  }
  return days
}

export function serializeCheckInDays(days: CheckInDays): string {
  return JSON.stringify(days)
}
