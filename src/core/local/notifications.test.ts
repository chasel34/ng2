import { describe, expect, it } from 'vitest'

import {
  groupNotifications,
  markRead,
  mergeNotifications,
  newNotifications,
  notificationId,
  unreadCount,
} from './notifications'

const item = (id: string, timestamp: number) => ({ id, timestamp })

describe('notificationId', () => {
  it('稳定 ID 是 时间戳-类型-tid-pid(spec §4)', () => {
    expect(notificationId({ timestamp: 1786100000, type: 2, tid: 44191387, pid: 812345678 })).toBe(
      '1786100000-2-44191387-812345678',
    )
  })

  it('短信类缺 tid/pid 用 0 占位,同一条两次拉取算出同一个 ID', () => {
    const first = notificationId({ timestamp: 1786080000, type: 10, tid: 0, pid: 0 })
    const second = notificationId({ timestamp: 1786080000, type: 10, tid: 0, pid: 0 })
    expect(first).toBe(second)
  })
})

describe('mergeNotifications(刷新只增不覆盖)', () => {
  it('重复拉取不重置已读:合并不碰已读集合,已读条目刷新后还是已读', () => {
    const feed = [item('a', 300), item('b', 200)]
    let items = mergeNotifications([], feed)
    let readIds = markRead(new Set<string>(), ['a'])
    expect(unreadCount(items, readIds)).toBe(1)

    // 服务端原样又发了一遍(轮询的常态)
    items = mergeNotifications(items, feed)
    expect(unreadCount(items, readIds)).toBe(1)

    // 全部读掉再刷新,未读数不回弹
    readIds = markRead(readIds, ['b'])
    items = mergeNotifications(items, feed)
    expect(unreadCount(items, readIds)).toBe(0)
  })

  it('新条目正确识别:只把没见过的 ID 插进来,并按时间降序落位', () => {
    const existing = mergeNotifications([], [item('a', 300), item('b', 200)])
    const incoming = [item('c', 400), item('a', 300), item('b', 200)]

    expect(newNotifications(existing, incoming).map((entry) => entry.id)).toEqual(['c'])

    const merged = mergeNotifications(existing, incoming)
    expect(merged.map((entry) => entry.id)).toEqual(['c', 'a', 'b'])
  })

  it('已认识的 ID 保留旧条目,不被新拉取覆盖', () => {
    const existing = [{ id: 'a', timestamp: 300, subject: '旧标题' }]
    const merged = mergeNotifications(existing, [{ id: 'a', timestamp: 300, subject: '新标题' }])
    expect(merged).toHaveLength(1)
    expect(merged[0]?.subject).toBe('旧标题')
  })
})

describe('markRead', () => {
  it('返回新集合,不改入参', () => {
    const before = new Set(['a'])
    const after = markRead(before, ['b'])
    expect(before.has('b')).toBe(false)
    expect(after.has('a')).toBe(true)
    expect(after.has('b')).toBe(true)
  })

  it('没有新增时返回原集合(引用相等,上层免写盘)', () => {
    const before = new Set(['a'])
    expect(markRead(before, ['a'])).toBe(before)
    expect(markRead(before, [])).toBe(before)
  })
})

describe('groupNotifications', () => {
  /** 分类集合由调用方定,这里用接口层那套(core/api 的 NotificationKind 同款字面量)。 */
  type Kind = 'reply' | 'mention' | 'comment' | 'message' | 'other'
  const noti = (id: string, kind: Kind, timestamp: number) => ({ id, kind, timestamp })

  it('按给定顺序分组,组内保持传入顺序,空组不出现', () => {
    const order: readonly Kind[] = ['reply', 'mention', 'comment', 'message']
    const groups = groupNotifications(
      [
        noti('m1', 'mention', 400),
        noti('r1', 'reply', 300),
        noti('m2', 'mention', 200),
        noti('c1', 'comment', 100),
      ],
      order,
    )
    expect(groups.map((group) => group.kind)).toEqual(['reply', 'mention', 'comment'])
    expect(groups[1]?.items.map((entry) => entry.id)).toEqual(['m1', 'm2'])
  })

  it('不在顺序表里的分类不丢,排在末尾', () => {
    const order: readonly Kind[] = ['reply']
    const groups = groupNotifications([noti('r', 'reply', 200), noti('x', 'other', 100)], order)
    expect(groups.map((group) => group.kind)).toEqual(['reply', 'other'])
  })
})
