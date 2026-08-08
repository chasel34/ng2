/**
 * 通知(CONTEXT.md「通知」)的接口解析(API 文档 §9)。
 *
 * `nuke.php?__lib=noti&__act=get_all` 一次拉全:`data["0"]` 下有三个容器
 * `"0"`(回复/@/贴条类)、`"1"`(短信类)、`"2"`,每条通知是数字下标对象。
 * 服务端**不提供逐条已读状态**——已读模型在 `core/local/notifications.ts`,
 * 这里只负责把响应解成条目并配上稳定 ID。
 *
 * 空账号的真实响应是 `{"data":{"0":""}}`——容器整个退化成空串,
 * 所以每一层都要先问 isRecord,空串就是空列表。
 */

import { NgaError, isRecord, type NgaFetcher } from '../net'
import { notificationId } from '../local'
import { int, nonZero, orderedValues, str } from './fields'
import type { NgaNotification, NotificationFeed, NotificationKind } from './types'

/**
 * 类型码 → 分类(API 文档 §9.1 的枚举表)。
 * 未认识的类型码归入 `other`——展示总比悄悄丢掉好。
 */
export function notificationKind(type: number): NotificationKind {
  switch (type) {
    case 1:
    case 2:
      return 'reply'
    case 3:
    case 4:
      return 'comment'
    case 7:
    case 8:
      return 'mention'
    case 10:
    case 11:
      return 'message'
    case 17:
      return 'rating'
    default:
      return 'other'
  }
}

/** 没标题的通知占位,与主题列表的口径一致。 */
const UNTITLED = '无标题'

/**
 * 解一条通知。类型码与时间戳是稳定 ID 的原料,缺了这两个的条目没法去重,
 * 只能跳过;其余字段短信类通知本来就没有(Android 研报 §10.1),全部给缺省值。
 */
function parseNotification(raw: unknown): NgaNotification | undefined {
  if (!isRecord(raw)) return undefined
  const type = int(raw, '0')
  const timestamp = int(raw, '9')
  if (type === undefined || timestamp === undefined) return undefined

  const tid = nonZero(int(raw, '6')) ?? 0
  const pid = nonZero(int(raw, '7')) ?? 0
  const userId = nonZero(int(raw, '1'))
  const userName = str(raw, '2')
  const myPid = nonZero(int(raw, '8'))
  const page = nonZero(int(raw, '10'))

  return {
    id: notificationId({ timestamp, type, tid, pid }),
    type,
    kind: notificationKind(type),
    ...(userId === undefined ? {} : { userId }),
    userName: userName ?? '匿名用户',
    subject: str(raw, '5') ?? UNTITLED,
    tid,
    pid,
    ...(myPid === undefined ? {} : { myPid }),
    timestamp,
    /** 对方帖子所在页码,服务端不给就当第 1 页 */
    page: page ?? 1,
  }
}

/** 容器既可能是数组也可能是数字下标对象,空账号下还会是空串。 */
function listItems(container: unknown): unknown[] {
  if (Array.isArray(container)) return container
  return orderedValues(container)
}

/**
 * 解整份通知列表。传响应的 `data`。
 *
 * 三个容器统一走同一个条目解析,分类看条目自己的类型码而不是所在容器——
 * 短信类通知(10/11)在哪个容器里出现都归 `message`。
 */
export function parseNotificationFeed(data: unknown): NotificationFeed {
  const root = isRecord(data) ? data : {}
  const box = isRecord(root['0']) ? root['0'] : {}

  const items: NgaNotification[] = []
  for (const key of ['0', '1', '2']) {
    for (const raw of listItems(box[key])) {
      const item = parseNotification(raw)
      if (item !== undefined) items.push(item)
    }
  }
  // 新的在前:服务端顺序不可靠,按时间戳降序稳定输出
  items.sort((a, b) => b.timestamp - a.timestamp)

  const unread = int(box, 'unread')
  return { items, ...(unread === undefined ? {} : { serverUnread: unread }) }
}

/** 拉全部通知(`POST nuke.php?__lib=noti&__act=get_all`,API 文档 §9.1)。 */
export async function fetchNotificationFeed(
  fetchNga: NgaFetcher,
  options: { readonly signal?: AbortSignal } = {},
): Promise<NotificationFeed> {
  const result = await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'noti', __act: 'get_all' },
    ...(options.signal === undefined ? {} : { signal: options.signal }),
  })

  if (!isRecord(result.data)) {
    throw new NgaError({ kind: 'parse', message: '通知响应里没有 data', via: result.via })
  }
  return parseNotificationFeed(result.data)
}

/**
 * 服务端一键清空(`POST nuke.php?__lib=noti&raw=3&__act=del`,API 文档 §9.2)。
 * 响应没有可用的数据,不抛错即成功。
 */
export async function clearNotificationFeed(fetchNga: NgaFetcher): Promise<void> {
  await fetchNga({
    path: 'nuke.php',
    query: { __lib: 'noti', raw: 3, __act: 'del' },
  })
}
