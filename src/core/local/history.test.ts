import { describe, expect, it } from 'vitest'

import {
  advanceHistoryFloor,
  formatHistoryTime,
  HISTORY_LIMIT,
  historyProgressLabel,
  isHistoryFinished,
  pageOfFloor,
  upsertHistory,
  type HistoryEntry,
} from './history'

const entry = (tid: number, extra: Partial<HistoryEntry> = {}): HistoryEntry => ({
  tid,
  subject: `主题 ${tid}`,
  lastFloor: 0,
  maxFloor: 40,
  updatedAt: 1000,
  ...extra,
})

describe('upsertHistory', () => {
  it('新主题插到最前', () => {
    const result = upsertHistory([entry(1)], { tid: 2, subject: '新主题', maxFloor: 10 }, 2000)
    expect(result.changed).toBe(true)
    expect(result.entries.map((item) => item.tid)).toEqual([2, 1])
    expect(result.entries[0]).toMatchObject({ subject: '新主题', lastFloor: 0, maxFloor: 10 })
  })

  it('同主题去重:更新时间与资料并挪到最前,而不是新增条目', () => {
    const before = [entry(1), entry(2, { lastFloor: 18, updatedAt: 1500 })]
    const updated = upsertHistory(
      before,
      { tid: 2, subject: '主题 2', boardName: '网事杂谈', maxFloor: 60 },
      3000,
    )
    expect(updated.entries).toHaveLength(2)
    expect(updated.entries[0]).toMatchObject({
      tid: 2,
      boardName: '网事杂谈',
      lastFloor: 18,
      maxFloor: 60,
      updatedAt: 3000,
    })
  })

  it('新值缺席时保留旧的元数据,楼层上限只前进', () => {
    const before = [entry(1, { author: '楼主甲', favCode: 'abc', maxFloor: 50 })]
    const result = upsertHistory(before, { tid: 1, subject: '主题 1', maxFloor: 30 }, 2000)
    expect(result.entries[0]).toMatchObject({ author: '楼主甲', favCode: 'abc', maxFloor: 50 })
  })

  it('超过上限时挤掉最老的一条并报告被淘汰的 tid', () => {
    const full = Array.from({ length: HISTORY_LIMIT }, (_, index) =>
      entry(index + 1, { updatedAt: 1000 + (HISTORY_LIMIT - index) }),
    )
    const result = upsertHistory(full, { tid: 9999, subject: '第 201 条' }, 5000)
    expect(result.entries).toHaveLength(HISTORY_LIMIT)
    expect(result.entries[0]?.tid).toBe(9999)
    expect(result.evictedTids).toEqual([HISTORY_LIMIT])
  })
})

describe('advanceHistoryFloor', () => {
  it('楼层前进时更新条目', () => {
    const result = advanceHistoryFloor([entry(1, { lastFloor: 3 })], 1, 18, 2000)
    expect(result.changed).toBe(true)
    expect(result.entries[0]).toMatchObject({ lastFloor: 18, updatedAt: 2000 })
  })

  it('楼层没前进就不动(滚动回调很勤,不能每次都写盘)', () => {
    const before = [entry(1, { lastFloor: 18 })]
    expect(advanceHistoryFloor(before, 1, 5, 2000).changed).toBe(false)
    expect(advanceHistoryFloor(before, 1, 18, 2000).changed).toBe(false)
    // changed 为 false 时必须返回原数组,适配器按引用相等跳过持久化
    expect(advanceHistoryFloor(before, 1, 5, 2000).entries).toBe(before)
  })

  it('条目不存在时丢弃上报,不造残缺行', () => {
    expect(advanceHistoryFloor([], 1, 18, 2000).changed).toBe(false)
  })

  it('看到比已知上限更大的楼层时把上限一起抬高', () => {
    const result = advanceHistoryFloor([entry(1, { maxFloor: 40 })], 1, 55, 2000)
    expect(result.entries[0]).toMatchObject({ lastFloor: 55, maxFloor: 55 })
  })

  it('把条目挪到最前:与重启后按 updatedAt 重排的顺序一致', () => {
    const before = [entry(1), entry(2)]
    const result = advanceHistoryFloor(before, 2, 9, 2000)
    expect(result.entries.map((item) => item.tid)).toEqual([2, 1])
  })
})

describe('进度文案', () => {
  it('读到最后一楼算读完', () => {
    expect(isHistoryFinished({ lastFloor: 40, maxFloor: 40 })).toBe(true)
    expect(isHistoryFinished({ lastFloor: 39, maxFloor: 40 })).toBe(false)
    expect(historyProgressLabel({ lastFloor: 40, maxFloor: 40 })).toBe('读完')
  })

  it('只有主楼的主题读过主楼就算读完', () => {
    expect(historyProgressLabel({ lastFloor: 0, maxFloor: 0 })).toBe('读完')
  })

  it('普通进度是「读到 N 楼」,只看过主楼是「读到主楼」', () => {
    expect(historyProgressLabel({ lastFloor: 18, maxFloor: 40 })).toBe('读到 18 楼')
    expect(historyProgressLabel({ lastFloor: 0, maxFloor: 40 })).toBe('读到主楼')
  })
})

describe('pageOfFloor', () => {
  it('主楼在第 1 页,每页最后一楼不越页', () => {
    expect(pageOfFloor(0, 20)).toBe(1)
    expect(pageOfFloor(19, 20)).toBe(1)
    expect(pageOfFloor(20, 20)).toBe(2)
    expect(pageOfFloor(45, 20)).toBe(3)
  })

  it('非法的每页行数退到 1 行一页而不是除以零', () => {
    expect(pageOfFloor(3, 0)).toBe(4)
  })
})

describe('formatHistoryTime', () => {
  // 取一个本地时间下午的基准点,避免跨日边界干扰:2026-08-08 15:00:00(本地)
  const now = Math.floor(new Date(2026, 7, 8, 15, 0, 0).getTime() / 1000)

  it('一分钟内是「刚刚」,一小时内按分钟算', () => {
    expect(formatHistoryTime(now - 30, now)).toBe('刚刚')
    expect(formatHistoryTime(now - 12 * 60, now)).toBe('12 分钟前')
  })

  it('今天/昨天带时刻,前天不带,更早给日期', () => {
    const at = (day: number, hour: number, minute: number) =>
      Math.floor(new Date(2026, 7, day, hour, minute).getTime() / 1000)
    expect(formatHistoryTime(at(8, 9, 14), now)).toBe('今天 09:14')
    expect(formatHistoryTime(at(7, 23, 41), now)).toBe('昨天 23:41')
    expect(formatHistoryTime(at(6, 12, 0), now)).toBe('前天')
    expect(formatHistoryTime(at(1, 12, 0), now)).toBe('2026-08-01')
  })

  it('凌晨刚过零点,昨晚的记录按日历日算「昨天」而不是按 24 小时窗口', () => {
    const midnight = Math.floor(new Date(2026, 7, 8, 0, 10, 0).getTime() / 1000)
    // 隔了 2 小时 29 分:按 24 小时窗口算会说成「今天」,按日历日才是「昨天」
    const lastNight = Math.floor(new Date(2026, 7, 7, 21, 41, 0).getTime() / 1000)
    expect(formatHistoryTime(lastNight, midnight)).toBe('昨天 21:41')
  })

  it('一小时内始终走相对时间,不因为跨了零点就改叫「昨天」', () => {
    const midnight = Math.floor(new Date(2026, 7, 8, 0, 10, 0).getTime() / 1000)
    const justBefore = Math.floor(new Date(2026, 7, 7, 23, 41, 0).getTime() / 1000)
    expect(formatHistoryTime(justBefore, midnight)).toBe('29 分钟前')
  })
})
