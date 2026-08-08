/**
 * 通知的本地已读模型(CONTEXT.md「通知」/ spec §4)。
 *
 * 服务端不提供逐条已读状态(API 文档 §9.1),本地维护:
 * - 稳定 ID `时间戳-类型-tid-pid`——同一条通知每次拉取都算出同一个 ID;
 * - **刷新只增不覆盖**:合并新一次拉取时,已认识的 ID 保留旧条目,
 *   只把没见过的条目插进来。已读集合是独立的一组 ID,合并根本不碰它,
 *   所以重复拉取天然不会重置已读。
 *
 * 纯 TS 零 RN 依赖;持久化(expo-sqlite)在 store 层适配。
 */

/** 稳定 ID 的原料:四个数字缺一不可,缺的位置由解析层补 0。 */
export interface NotificationIdParts {
  readonly timestamp: number
  readonly type: number
  readonly tid: number
  readonly pid: number
}

/** 稳定 ID `时间戳-类型-tid-pid`(spec §4,MNGA 同款口径)。 */
export function notificationId(parts: NotificationIdParts): string {
  return `${parts.timestamp}-${parts.type}-${parts.tid}-${parts.pid}`
}

/** 已读模型只关心「有稳定 ID、可按时间排序」,不绑死接口层的具体形状。 */
export interface NotificationLike {
  readonly id: string
  readonly timestamp: number
}

/**
 * 把新一次拉取合并进已有条目:**只增不覆盖**。
 *
 * 已认识的 ID 保留旧条目(服务端偶尔会对同一条通知微调字段,
 * 覆盖会让「同一条」在两次刷新之间变脸);新条目插入后整体按时间降序。
 */
export function mergeNotifications<T extends NotificationLike>(
  existing: readonly T[],
  incoming: readonly T[],
): T[] {
  const seen = new Set(existing.map((item) => item.id))
  const merged = [...existing]
  for (const item of incoming) {
    if (seen.has(item.id)) continue
    seen.add(item.id)
    merged.push(item)
  }
  return merged.sort((a, b) => b.timestamp - a.timestamp)
}

/** 这次拉取里哪些是没见过的新条目(未读角标要立刻反映它们)。 */
export function newNotifications<T extends NotificationLike>(
  existing: readonly T[],
  incoming: readonly T[],
): T[] {
  const seen = new Set(existing.map((item) => item.id))
  return incoming.filter((item) => !seen.has(item.id))
}

/** 未读数:条目里不在已读集合里的那些。 */
export function unreadCount(
  items: readonly NotificationLike[],
  readIds: ReadonlySet<string>,
): number {
  let count = 0
  for (const item of items) {
    if (!readIds.has(item.id)) count += 1
  }
  return count
}

/**
 * 标记一批 ID 为已读。返回新集合(不改入参);一个都没变时返回原集合,
 * 方便上层拿引用相等判断要不要写盘。
 */
export function markRead(
  readIds: ReadonlySet<string>,
  ids: readonly string[],
): ReadonlySet<string> {
  const fresh = ids.filter((id) => !readIds.has(id))
  if (fresh.length === 0) return readIds
  const next = new Set(readIds)
  for (const id of fresh) next.add(id)
  return next
}

/** 按分类分组,组内保持传入顺序(调用方已按时间降序)。空组不出现。 */
export function groupNotifications<K extends string, T extends { readonly kind: K }>(
  items: readonly T[],
  order: readonly K[],
): { readonly kind: K; readonly items: T[] }[] {
  const buckets = new Map<K, T[]>()
  for (const item of items) {
    const bucket = buckets.get(item.kind)
    if (bucket === undefined) buckets.set(item.kind, [item])
    else bucket.push(item)
  }
  const known = new Set<K>(order)
  const tail = [...buckets.keys()].filter((kind) => !known.has(kind))
  return [...order, ...tail]
    .map((kind) => ({ kind, items: buckets.get(kind) ?? [] }))
    .filter((group) => group.items.length > 0)
}
