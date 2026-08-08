/**
 * 二级列表(设计稿 simple-list:热帖/精华区)信息行右侧的时间文案。
 *
 * 热帖用相对时间(设计稿样例:「刚刚 / 12 分钟前 / 1 小时前」),
 * 精华区用日期(设计稿样例:「2026-07-20」)。`now` 一律由调用方传入,
 * 热帖页拿榜单算出的时刻当基准,刷新前文案不跳动。
 */

/** 秒级 unix 时间戳 → `YYYY-MM-DD`(设备时区)。 */
export function dateText(atSeconds: number): string {
  const date = new Date(atSeconds * 1000);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/** 秒级 unix 时间戳 → 相对时间。超过一天(热帖窗口外)退回日期。 */
export function relativeTimeText(atSeconds: number, nowMs: number): string {
  const elapsed = Math.floor(nowMs / 1000) - atSeconds;
  if (elapsed < 60) return '刚刚';
  if (elapsed < 3600) return `${Math.floor(elapsed / 60)} 分钟前`;
  if (elapsed < 24 * 3600) return `${Math.floor(elapsed / 3600)} 小时前`;
  return dateText(atSeconds);
}
