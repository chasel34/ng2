import { describe, expect, it } from 'vitest'

import {
  cachePagesLabel,
  cacheTotalBytes,
  formatCacheSize,
  planCacheEviction,
  summarizeCachedPages,
  utf8ByteLength,
  type CachedPage,
} from './topic-cache'

const page = (overrides: Partial<CachedPage> & Pick<CachedPage, 'tid' | 'page'>): CachedPage => ({
  subject: `主题 ${overrides.tid}`,
  floors: 20,
  totalPages: 3,
  bytes: 1000,
  usedAt: 1_000,
  ...overrides,
})

describe('summarizeCachedPages', () => {
  it('同一主题的页聚合成一条,字节相加、时间取最近', () => {
    const topics = summarizeCachedPages([
      page({ tid: 7, page: 2, bytes: 300, usedAt: 500 }),
      page({ tid: 7, page: 1, bytes: 200, usedAt: 900 }),
    ])

    expect(topics).toHaveLength(1)
    expect(topics[0]).toMatchObject({ tid: 7, pages: [1, 2], bytes: 500, usedAt: 900 })
  })

  it('元数据以最近写入的那一页为准,但缺席时保留旧值', () => {
    const topics = summarizeCachedPages([
      page({ tid: 7, page: 1, subject: '旧标题', boardName: '硬件', usedAt: 100 }),
      // 从第 2 页写入时拿不到版块名(__F 只有部分响应带)
      page({ tid: 7, page: 2, subject: '新标题', usedAt: 200 }),
    ])

    expect(topics[0]?.subject).toBe('新标题')
    expect(topics[0]?.boardName).toBe('硬件')
  })

  it('按最近使用倒序,这就是「我的缓存」页的顺序', () => {
    const topics = summarizeCachedPages([
      page({ tid: 1, page: 1, usedAt: 100 }),
      page({ tid: 2, page: 1, usedAt: 300 }),
      page({ tid: 3, page: 1, usedAt: 200 }),
    ])

    expect(topics.map((topic) => topic.tid)).toEqual([2, 3, 1])
  })
})

describe('planCacheEviction', () => {
  const topics = (count: number, bytes: number) =>
    summarizeCachedPages(
      Array.from({ length: count }, (_, index) =>
        page({ tid: index + 1, page: 1, bytes, usedAt: index + 1 }),
      ),
    )

  it('主题数超限时从最久未用的开始淘汰', () => {
    expect(planCacheEviction(topics(5, 100), { maxTopics: 3 })).toEqual([1, 2])
  })

  it('字节数超限时同样按最久未用淘汰,淘汰到不超为止', () => {
    // 4 个主题 × 100 字节,上限 250 → 掉最老的两个
    expect(planCacheEviction(topics(4, 100), { maxBytes: 250 })).toEqual([1, 2])
  })

  it('淘汰是整主题走的:一个主题的几页要么全留要么全删', () => {
    const list = summarizeCachedPages([
      page({ tid: 1, page: 1, bytes: 100, usedAt: 10 }),
      page({ tid: 1, page: 2, bytes: 100, usedAt: 20 }),
      page({ tid: 2, page: 1, bytes: 100, usedAt: 30 }),
    ])
    expect(planCacheEviction(list, { maxBytes: 150 })).toEqual([1])
  })

  it('没超限时一个都不淘汰', () => {
    expect(planCacheEviction(topics(3, 100), { maxTopics: 10, maxBytes: 1000 })).toEqual([])
  })

  it('最近用的那个主题永远留着——哪怕它自己就超过字节上限', () => {
    expect(planCacheEviction(topics(2, 900), { maxBytes: 100 })).toEqual([1])
  })
})

describe('cachePagesLabel', () => {
  it('只缓存一页时顺带报楼数(设计稿「第 1 页 · 40 楼」)', () => {
    expect(cachePagesLabel({ pages: [1], floors: 40 })).toBe('第 1 页 · 40 楼')
  })

  it('连续页压成区间', () => {
    expect(cachePagesLabel({ pages: [1, 2, 3], floors: 20 })).toBe('第 1–3 页')
  })

  it('不连续的页分段列出来', () => {
    expect(cachePagesLabel({ pages: [1, 2, 5], floors: 20 })).toBe('第 1–2、5 页')
  })

  it('段数太多时只列前几段,末尾报总页数', () => {
    expect(cachePagesLabel({ pages: [1, 3, 5, 7, 9], floors: 20 })).toBe('第 1、3、5 等 5 页')
  })
})

describe('formatCacheSize', () => {
  it('照设计稿的口径给出人读的大小', () => {
    expect(formatCacheSize(512)).toBe('512 B')
    expect(formatCacheSize(4096)).toBe('4 KB')
    expect(formatCacheSize(1.2 * 1024 * 1024)).toBe('1.2 MB')
    expect(formatCacheSize(42.6 * 1024 * 1024)).toBe('42.6 MB')
    expect(formatCacheSize(2 * 1024 * 1024 * 1024)).toBe('2.00 GB')
  })
})

describe('utf8ByteLength', () => {
  it('与 TextEncoder 的结果一致(ASCII / 中文 / emoji / 落单代理项)', () => {
    const encoder = new TextEncoder()
    for (const text of ['', 'abc', '网事杂谈', 'a中🀄️b', '楼层\u{1F600}', '\ud800']) {
      expect(utf8ByteLength(text)).toBe(encoder.encode(text).length)
    }
  })
})

describe('cacheTotalBytes', () => {
  it('把各主题的占用加起来(缓存页副标题那句)', () => {
    const topics = summarizeCachedPages([
      page({ tid: 1, page: 1, bytes: 1024 }),
      page({ tid: 2, page: 1, bytes: 2048 }),
    ])
    expect(cacheTotalBytes(topics)).toBe(3072)
  })
})
