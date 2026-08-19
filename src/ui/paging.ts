/**
 * 详情页翻页的全部算术。
 *
 * 页码条、跳页对话框、左右滑动是三个入口,但**只能有一套页码规则**——
 * 各自写一遍夹逼与边界判断,迟早会出现「滑动能到第 0 页而页码条不能」这种不一致。
 * 三个入口都从这里取值,一致性就是结构性的,不靠人盯。
 *
 * 纯函数、零 RN 依赖,所以能直接单测(组件本身在本仓库跑不了渲染测试)。
 *
 * 滑动那一路(`swipe*` + `clampPage`)标了 `'worklet'`:横滑翻页跑在 Reanimated 的
 * UI 线程上,跨模块调用的函数不标就搬不过去。指令在 JS 线程上是一句无害的字符串,
 * 页码条与跳页对话框照常直接调,单测也照跑。
 *
 * 首页的分类横滑(`app/index.tsx`)也走这套:它的「页」是第几个 tab,
 * 规则一样是「1 – count 之间、松手看位移与速度」,没必要再写一份。
 */

/** 页码窗口:当前页前后各露几格。 */
const WINDOW_RADIUS = 4;

/**
 * 松手翻页的位移门槛,取**屏宽的比例**而不是固定像素。
 *
 * 原来是写死的 60px:跟手系数 0.7,屏上只跟出 42px——1080p 手机上约屏宽的 11%,
 * 竖着滚楼层时手指带出的那点横向位移就够翻页了,误触是常态。0.28 这一档是照
 * 系统分页器的手感定的(iOS/Android 的 pager 都在 1/4 – 1/3 之间),
 * 大屏小屏都按同一个「走了小半屏」来判断。
 */
export const SWIPE_COMMIT_RATIO = 0.28;

/**
 * 甩动翻页:快速一划不该要求走满小半屏。速度门槛按 px/ms 取
 * (gesture-handler 给的是 px/s,除以 1000),再配一个最小位移,
 * 免得点击时手指抖那一两像素被当成甩动。
 */
export const SWIPE_FLING_VELOCITY = 0.4;
export const SWIPE_FLING_DISTANCE = 24;

/**
 * 走够这么多才认成横滑(且横向位移要明显压过纵向,不然抢了列表的上下滚动)。
 * 认领早不等于翻页早——翻不翻页由上面那两个门槛说了算,所以这一档保持灵敏。
 */
export const SWIPE_ACTIVATE = 12;

/** 「明显压过纵向」是多明显。横向位移要到纵向的这个倍数才认领。 */
export const SWIPE_AXIS_RATIO = 1.3;

/** 夹到 `1 – totalPages`。非法输入(NaN / 小数 / 负数)一律退到第 1 页。 */
export function clampPage(page: number, totalPages: number): number {
  'worklet';
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
export const swipeDirection = (dx: number): 1 | -1 => {
  'worklet';
  return dx < 0 ? 1 : -1;
};

/** 这块屏上「走够了」是多远。宽度非法时退到一个不会把门槛降到 0 的下限。 */
export function swipeCommitDistance(width: number): number {
  'worklet';
  return Math.max(1, width) * SWIPE_COMMIT_RATIO;
}

/**
 * 松手后该停在哪一页。
 *
 * 两条路都算数:内容的视觉位移(`reach`)走满 `swipeCommitDistance`,或者甩得
 * 够快(速度过门槛且这一把的位移不只是手抖)。缺了后者的话,提高位移门槛就等于
 * 「必须慢慢拖到小半屏」,快速翻页反而变难了——这两条是配套的,不能只改一条。
 *
 * `reach` 与 `dx` 分开收:半路接管收尾动画时,视觉位移里带着上一把的余位,
 * 方向可能与这一把的甩动相反——甩动看**速度的方向**,慢拖看**位置停在哪半边**。
 * 混用一个值的话,快速连甩会因为余位没消化完被判成「往回翻」。普通手势里两者相等。
 *
 * 已经在头尾、或者两条都没够,就返回原页码(调用方据此判断「要不要翻」)。
 */
export function swipeTargetPage(
  page: number,
  /** 内容离当前页基准的视觉位移(接管时含上一把的余位) */
  reach: number,
  /** 这一把手势自己的位移(甩动的防手抖门槛用它) */
  dx: number,
  totalPages: number,
  width: number,
  /** px/ms,往左划为负 */
  velocity: number,
): number {
  'worklet';
  const flung =
    Math.abs(velocity) >= SWIPE_FLING_VELOCITY && Math.abs(dx) >= SWIPE_FLING_DISTANCE;
  if (flung) return clampPage(page + (velocity < 0 ? 1 : -1), totalPages);
  if (Math.abs(reach) >= swipeCommitDistance(width)) {
    return clampPage(page + swipeDirection(reach), totalPages);
  }
  return page;
}

/**
 * 拖动过程中内容实际跟手的位移。
 *
 * 中段是 1:1 —— 相邻页就贴在当前页两边一起走,跟手打折等于让手指和页面错位,
 * 一眼看得出来(原来的 0.7 是没有相邻页时用来「少走一点」的补偿,现在反而是穿帮)。
 * 到头时给强阻尼,手感上「拉不动了」,这也是「已经是第一页/最后一页」的唯一反馈——
 * 相邻页真的跟出来之后,原来那块「第 N 页」浮层就没有存在的必要了。
 */
export function swipeOffset(page: number, dx: number, totalPages: number): number {
  'worklet';
  const edgeFollow = 0.22;
  const atEdge =
    (dx > 0 && page <= 1) || (dx < 0 && page >= Math.max(1, Math.trunc(totalPages)));
  return atEdge ? dx * edgeFollow : dx;
}
