import { describe, expect, it } from 'vitest'

import { aggregateHotTopics, type HotTopicCandidate } from './hot-topics'

/** 造一条候选主题。时间统一用「距 NOW 多少秒之前」写,免得测试里满屏时间戳。 */
const NOW = 1_800_000_000

function topic(
  tid: number,
  overrides: Partial<HotTopicCandidate> & { postedAgo?: number } = {},
): HotTopicCandidate {
  const { postedAgo = 3600, ...rest } = overrides
  return {
    tid,
    replies: 0,
    postedAt: NOW - postedAgo,
    lastPostAt: NOW - 60,
    ...rest,
  }
}

const tids = (topics: readonly HotTopicCandidate[]) => topics.map((t) => t.tid)

describe('aggregateHotTopics', () => {
  it('按回复数降序排', () => {
    const result = aggregateHotTopics(
      [[topic(1, { replies: 10 }), topic(2, { replies: 300 }), topic(3, { replies: 42 })]],
      { now: NOW },
    )
    expect(tids(result)).toEqual([2, 3, 1])
  })

  it('回复数相同的按最后回复时间降序,再相同的按 tid 升序,结果确定', () => {
    const result = aggregateHotTopics(
      [
        [
          topic(3, { replies: 5, lastPostAt: NOW - 100 }),
          topic(1, { replies: 5, lastPostAt: NOW - 10 }),
          topic(4, { replies: 5, lastPostAt: NOW - 100 }),
          topic(2, { replies: 5, lastPostAt: NOW - 100 }),
        ],
      ],
      { now: NOW },
    )
    expect(tids(result)).toEqual([1, 2, 3, 4])
  })

  it('只留窗口内发帖的主题:24h 边界内的算,之外的不算', () => {
    const result = aggregateHotTopics(
      [
        [
          topic(1, { postedAgo: 24 * 3600 }), // 恰好 24h,含边界
          topic(2, { postedAgo: 24 * 3600 + 1 }), // 过线 1 秒
          topic(3, { postedAgo: 10 }),
        ],
      ],
      { now: NOW },
    )
    expect(tids(result).sort()).toEqual([1, 3])
  })

  it('窗口过滤看发帖时间而不是最后回复:被顶起来的老坟不进榜', () => {
    const grave = topic(1, { postedAgo: 300 * 24 * 3600, lastPostAt: NOW - 5, replies: 9999 })
    expect(aggregateHotTopics([[grave]], { now: NOW })).toEqual([])
  })

  it('窗口小时数可配', () => {
    const pages = [[topic(1, { postedAgo: 2 * 3600 }), topic(2, { postedAgo: 30 * 60 })]]
    expect(tids(aggregateHotTopics(pages, { now: NOW, windowHours: 1 }))).toEqual([2])
    expect(tids(aggregateHotTopics(pages, { now: NOW, windowHours: 24 })).sort()).toEqual([1, 2])
  })

  it('跨页按 tid 去重:置顶主题每页都会再回来一次', () => {
    const result = aggregateHotTopics(
      [
        [topic(1, { replies: 7 }), topic(2, { replies: 3 })],
        [topic(1, { replies: 7 }), topic(3, { replies: 5 })],
      ],
      { now: NOW },
    )
    expect(tids(result)).toEqual([1, 3, 2])
  })

  it('合集/镜像行与外链活动主题不进榜:它们不是讨论串', () => {
    const result = aggregateHotTopics(
      [
        [
          topic(1, { replies: 100, shortcut: { kind: 'board', id: 650 } }),
          topic(2, { replies: 50, jumpUrl: 'https://nga.178.com/misc/lottery.html' }),
          topic(3, { replies: 1 }),
        ],
      ],
      { now: NOW },
    )
    expect(tids(result)).toEqual([3])
  })

  it('postedAt 解析失败退到 0 的坏条目被窗口自然挡掉', () => {
    const result = aggregateHotTopics([[topic(1, { postedAt: 0 }), topic(2)]], { now: NOW })
    expect(tids(result)).toEqual([2])
  })

  it('没有任何页或页全空时返回空榜', () => {
    expect(aggregateHotTopics([], { now: NOW })).toEqual([])
    expect(aggregateHotTopics([[], []], { now: NOW })).toEqual([])
  })
})
