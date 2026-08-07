/**
 * 详情页翻页的全部算术。
 *
 * 页码条、跳页对话框、左右滑动是三个入口,但**只能有一套页码规则**——
 * 各自写一遍夹逼与边界判断,迟早会出现「滑动能到第 0 页而页码条不能」这种不一致。
 * 三个入口都从这里取值,一致性就是结构性的,不靠人盯。
 *
 * 纯函数、零 RN 依赖,所以能直接单测(组件本身在本仓库跑不了渲染测试)。
 */

/** 页码窗口:当前页前后各露几格。 */
const WINDOW_RADIUS = 4;

/** 松手时横向位移超过这个数才真翻页(设计稿 isArticle 的 `swipeUp`)。 */
export const SWIPE_COMMIT_DISTANCE = 60;

/** 拖过这个距离才浮出「第 N 页」提示(设计稿 `swipeHint`)。 */
export const SWIPE_HINT_DISTANCE = 40;

/** 夹到 `1 – totalPages`。非法输入(NaN / 小数 / 负数)一律退到第 1 页。 */
export function clampPage(page: number, totalPages: number): number {
  if (!Number.isFinite(page)) return 1;
  const total = Math.max(1, Math.trunc(totalPages));
  return Math.min(Math.max(1, Math.trunc(page)), total);
}

/**
 * 页码条上要画哪几格:当前页前后各 `WINDOW_RADIUS` 格,外加固定露出的首尾页。
 * 上千页的帖子全铺出来 ScrollView 会卡,所以只画一个窗口。
 */
export function visiblePages(page: number, totalPages: number): number[] {
  const total = Math.max(1, Math.trunc(totalPages));
  const current = clampPage(page, total);
  const window = new Set<number>([1, total]);
  for (let value = current - WINDOW_RADIUS; value <= current + WINDOW_RADIUS; value += 1) {
    if (value >= 1 && value <= total) window.add(value);
  }
  return [...window].sort((a, b) => a - b);
}

/**
 * 跳页对话框输进来的那串东西。
 * 与另外两个入口不同,这里**不夹逼**——用户手打了 999,该告诉他超范围,
 * 而不是默默跳到最后一页。
 */
export function parseJumpTarget(input: string, totalPages: number): number | undefined {
  const value = Number(input.trim());
  if (!Number.isInteger(value)) return undefined;
  return value >= 1 && value <= Math.max(1, Math.trunc(totalPages)) ? value : undefined;
}

/** 手指往左划(dx < 0)是下一页,往右是上一页。 */
export const swipeDirection = (dx: number): 1 | -1 => (dx < 0 ? 1 : -1);

/**
 * 松手后该停在哪一页。没走够 `SWIPE_COMMIT_DISTANCE`、或者已经在头尾了,
 * 就返回原页码(调用方据此判断「要不要翻」)。
 */
export function swipeTargetPage(page: number, dx: number, totalPages: number): number {
  if (Math.abs(dx) <= SWIPE_COMMIT_DISTANCE) return page;
  return clampPage(page + swipeDirection(dx), totalPages);
}

/**
 * 拖动过程中那个浮层写什么。没拖够就不显示(返回 undefined)。
 * 到头时明说「已是第一页 / 已是最后一页」——设计稿就是这么写的,
 * 比让人对着不动的画面猜要好。
 */
export function swipeHintText(
  page: number,
  dx: number,
  totalPages: number,
): string | undefined {
  if (Math.abs(dx) <= SWIPE_HINT_DISTANCE) return undefined;
  const target = page + swipeDirection(dx);
  if (target < 1) return '已是第一页';
  if (target > Math.max(1, Math.trunc(totalPages))) return '已是最后一页';
  return `第 ${target} 页`;
}

/** 拖到头时给强阻尼,手感上"拉不动了"。返回内容实际跟手的位移。 */
export function swipeOffset(page: number, dx: number, totalPages: number): number {
  const follow = 0.7;
  const edgeFollow = 0.25;
  const atEdge =
    (dx > 0 && page <= 1) || (dx < 0 && page >= Math.max(1, Math.trunc(totalPages)));
  return dx * (atEdge ? edgeFollow : follow);
}
