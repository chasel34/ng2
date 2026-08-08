import { describe, expect, it } from 'vitest'

import {
  EMPTY_CHECK_IN_DAYS,
  beijingDayKey,
  isCheckedInOn,
  markCheckedIn,
  parseCheckInDays,
  serializeCheckInDays,
} from './check-in'

/** UTC 时刻的字面量,便于把「北京时间的午夜」写清楚。 */
const at = (iso: string) => Date.parse(iso)

describe('beijingDayKey', () => {
  it('按 UTC+8 取日期,不是设备时区也不是 UTC', () => {
    // UTC 的 8 月 7 日 17:00 已经是北京时间 8 月 8 日凌晨 1 点
    expect(beijingDayKey(at('2026-08-07T17:00:00Z'))).toBe('2026-08-08')
    expect(beijingDayKey(at('2026-08-07T15:59:59Z'))).toBe('2026-08-07')
  })

  it('北京时间的日界线正好落在 UTC 16:00', () => {
    expect(beijingDayKey(at('2026-08-07T16:00:00Z'))).toBe('2026-08-08')
  })
})

describe('isCheckedInOn / markCheckedIn', () => {
  const morning = at('2026-08-08T01:00:00Z') // 北京时间 8/8 09:00
  const night = at('2026-08-08T15:00:00Z') // 同一天 23:00
  const nextDay = at('2026-08-08T16:30:00Z') // 已经是 8/9 00:30

  it('签过之后当天不再签,跨过 UTC+8 的日界线就该再签', () => {
    const days = markCheckedIn(EMPTY_CHECK_IN_DAYS, '42', morning)

    expect(isCheckedInOn(days, '42', morning)).toBe(true)
    expect(isCheckedInOn(days, '42', night)).toBe(true)
    expect(isCheckedInOn(days, '42', nextDay)).toBe(false)
  })

  it('每个账号各记各的:一个号签了不算另一个号签了', () => {
    const days = markCheckedIn(EMPTY_CHECK_IN_DAYS, '42', morning)

    expect(isCheckedInOn(days, '43', morning)).toBe(false)
    expect(isCheckedInOn(markCheckedIn(days, '43', morning), '42', morning)).toBe(true)
  })

  it('同一账号只留最后一天,不攒历史', () => {
    const days = markCheckedIn(markCheckedIn(EMPTY_CHECK_IN_DAYS, '42', morning), '42', nextDay)

    expect(days).toEqual({ '42': '2026-08-09' })
  })

  it('不改原对象', () => {
    const days = markCheckedIn(EMPTY_CHECK_IN_DAYS, '42', morning)
    markCheckedIn(days, '43', morning)

    expect(days).toEqual({ '42': '2026-08-08' })
  })
})

describe('parseCheckInDays / serializeCheckInDays', () => {
  it('存进去再读回来是同一份', () => {
    const days = { '42': '2026-08-08', '43': '2026-08-07' }

    expect(parseCheckInDays(serializeCheckInDays(days))).toEqual(days)
  })

  it('空、坏 JSON、非对象一律当没签过', () => {
    expect(parseCheckInDays(null)).toEqual({})
    expect(parseCheckInDays('')).toEqual({})
    expect(parseCheckInDays('{ 不是 JSON')).toEqual({})
    expect(parseCheckInDays('["42"]')).toEqual({})
  })

  it('值不是日期形状的条目丢掉,其余照收', () => {
    expect(parseCheckInDays('{"42":"2026-08-08","43":1,"44":"昨天"}')).toEqual({
      '42': '2026-08-08',
    })
  })
})
