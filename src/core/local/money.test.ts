import { describe, expect, it } from 'vitest'

import { formatMoney, formatReputation, splitMoney, toReputation } from './money'

describe('splitMoney', () => {
  it('按 10000 / 100 两级进位拆成金银铜', () => {
    expect(splitMoney(0)).toEqual({ gold: 0, silver: 0, copper: 0, negative: false })
    expect(splitMoney(1)).toEqual({ gold: 0, silver: 0, copper: 1, negative: false })
    expect(splitMoney(100)).toEqual({ gold: 0, silver: 1, copper: 0, negative: false })
    expect(splitMoney(10000)).toEqual({ gold: 1, silver: 0, copper: 0, negative: false })
    expect(splitMoney(123456)).toEqual({ gold: 12, silver: 34, copper: 56, negative: false })
  })

  it('负余额按绝对值拆，符号单独标出来', () => {
    // 直接对负数取模会拆出 -1/-23/-45 这种读不出来的东西
    expect(splitMoney(-12345)).toEqual({ gold: 1, silver: 23, copper: 45, negative: true })
  })

  it('小数与非法值先规整成整数铜币', () => {
    expect(splitMoney(150.9)).toEqual({ gold: 0, silver: 1, copper: 50, negative: false })
    expect(splitMoney(Number.NaN)).toEqual({ gold: 0, silver: 0, copper: 0, negative: false })
  })
})

describe('formatMoney', () => {
  it('设计稿基础信息卡的 `金.银.铜` 文案', () => {
    expect(formatMoney(0)).toBe('0.0.0')
    expect(formatMoney(123456)).toBe('12.34.56')
    expect(formatMoney(-12345)).toBe('-1.23.45')
  })
})

describe('威望 ÷10', () => {
  it('服务端的 rvrc/fame 显示时除以 10', () => {
    expect(toReputation(15)).toBe(1.5)
    expect(toReputation(10)).toBe(1)
    expect(toReputation(0)).toBe(0)
  })

  it('负威望照除不误', () => {
    // 真实样本 uid=2 的 rvrc 是 -11109
    expect(toReputation(-11109)).toBeCloseTo(-1110.9)
    expect(formatReputation(toReputation(-11109))).toBe('-1110.9')
  })

  it('文案收的是已经除过 10 的显示值，固定一位小数（设计稿楼层头 `威望 1.0`）', () => {
    expect(formatReputation(1)).toBe('1.0')
    expect(formatReputation(12.4)).toBe('12.4')
    expect(formatReputation(toReputation(124))).toBe('12.4')
  })
})
