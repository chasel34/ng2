import { describe, expect, it } from 'vitest'

import { dateText, relativeTimeText } from './time-text'

const NOW_MS = 1_800_000_000_000
const nowSec = NOW_MS / 1000

describe('relativeTimeText', () => {
  it('一分钟内是「刚刚」', () => {
    expect(relativeTimeText(nowSec - 5, NOW_MS)).toBe('刚刚')
    expect(relativeTimeText(nowSec - 59, NOW_MS)).toBe('刚刚')
  })

  it('一小时内按分钟,一天内按小时(设计稿样例文案)', () => {
    expect(relativeTimeText(nowSec - 12 * 60, NOW_MS)).toBe('12 分钟前')
    expect(relativeTimeText(nowSec - 3600, NOW_MS)).toBe('1 小时前')
    expect(relativeTimeText(nowSec - 5 * 3600 - 120, NOW_MS)).toBe('5 小时前')
  })

  it('超过一天退回日期', () => {
    expect(relativeTimeText(nowSec - 3 * 24 * 3600, NOW_MS)).toBe(
      dateText(nowSec - 3 * 24 * 3600),
    )
  })

  it('服务端时钟略超前时不出负数', () => {
    expect(relativeTimeText(nowSec + 30, NOW_MS)).toBe('刚刚')
  })
})

describe('dateText', () => {
  it('YYYY-MM-DD,月日补零', () => {
    // 2026-08-08 12:00 UTC:任何时区里月/日都是两位
    expect(dateText(1786536000)).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})
