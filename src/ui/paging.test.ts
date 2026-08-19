import { describe, expect, it } from 'vitest';

import {
  SWIPE_COMMIT_RATIO,
  SWIPE_FLING_DISTANCE,
  SWIPE_FLING_VELOCITY,
  clampPage,
  parseJumpTarget,
  swipeCommitDistance,
  swipeOffset,
  swipeTargetPage,
  visiblePages,
} from './paging';

describe('clampPage', () => {
  it('夹在 1 – totalPages 之间', () => {
    expect(clampPage(0, 13)).toBe(1);
    expect(clampPage(-5, 13)).toBe(1);
    expect(clampPage(7, 13)).toBe(7);
    expect(clampPage(99, 13)).toBe(13);
  });

  it('页数为 0 或负时仍然有第 1 页', () => {
    expect(clampPage(1, 0)).toBe(1);
    expect(clampPage(3, -2)).toBe(1);
  });

  it('非法页码退到第 1 页', () => {
    expect(clampPage(Number.NaN, 13)).toBe(1);
    expect(clampPage(Number.POSITIVE_INFINITY, 13)).toBe(1);
    expect(clampPage(3.7, 13)).toBe(3);
  });
});

describe('visiblePages', () => {
  it('页数少时全部铺出来', () => {
    expect(visiblePages(1, 5)).toEqual([1, 2, 3, 4, 5]);
  });

  it('页数多时只画当前页附近的窗口,首尾两页固定露出', () => {
    const pages = visiblePages(50, 200);
    expect(pages[0]).toBe(1);
    expect(pages.at(-1)).toBe(200);
    expect(pages).toContain(50);
    expect(pages).toContain(46);
    expect(pages).toContain(54);
    expect(pages).not.toContain(45);
  });

  it('永远升序且不重复', () => {
    for (const page of [1, 2, 7, 199, 200]) {
      const pages = visiblePages(page, 200);
      expect(pages).toEqual([...pages].sort((a, b) => a - b));
      expect(new Set(pages).size).toBe(pages.length);
    }
  });

  it('只有一页时就一格', () => {
    expect(visiblePages(1, 1)).toEqual([1]);
  });
});

describe('parseJumpTarget', () => {
  it('认合法页码', () => {
    expect(parseJumpTarget('7', 13)).toBe(7);
    expect(parseJumpTarget(' 13 ', 13)).toBe(13);
  });

  it('超范围返回 undefined —— 跳页不夹逼,要让用户知道输错了', () => {
    expect(parseJumpTarget('0', 13)).toBeUndefined();
    expect(parseJumpTarget('14', 13)).toBeUndefined();
    expect(parseJumpTarget('-3', 13)).toBeUndefined();
  });

  it('不是整数一律拒', () => {
    for (const bad of ['', 'abc', '3.5', '1e3x', ' ']) {
      expect(parseJumpTarget(bad, 13)).toBeUndefined();
    }
  });
});

describe('滑动翻页', () => {
  /** 一块 390dp 宽的屏,门槛 = 390 × 0.28 ≈ 109.2 */
  const W = 390;
  const commit = swipeCommitDistance(W);
  /** 慢慢拖到手停住:速度那一路不该掺进来 */
  const slow = 0;

  it('往左划下一页、往右划上一页', () => {
    expect(swipeTargetPage(5, -commit, -commit, 13, W, slow)).toBe(6);
    expect(swipeTargetPage(5, commit, commit, 13, W, slow)).toBe(4);
  });

  it('没走够阈值又没甩起来就不翻', () => {
    expect(swipeTargetPage(5, -commit + 1, -commit + 1, 13, W, slow)).toBe(5);
    expect(swipeTargetPage(5, 10, 10, 13, W, slow)).toBe(5);
  });

  it('门槛跟着屏宽走:同样的 80px 在小屏上够、在大屏上不够', () => {
    expect(swipeTargetPage(5, -80, -80, 13, 240, slow)).toBe(6);
    expect(swipeTargetPage(5, -80, -80, 13, 800, slow)).toBe(5);
  });

  it('甩得够快就不必走满小半屏', () => {
    expect(
      swipeTargetPage(5, -SWIPE_FLING_DISTANCE, -SWIPE_FLING_DISTANCE, 13, W, -SWIPE_FLING_VELOCITY),
    ).toBe(6);
    expect(
      swipeTargetPage(5, SWIPE_FLING_DISTANCE, SWIPE_FLING_DISTANCE, 13, W, SWIPE_FLING_VELOCITY),
    ).toBe(4);
  });

  it('甩得快但几乎没动 = 手抖,不算', () => {
    expect(swipeTargetPage(5, -3, -3, 13, W, -2)).toBe(5);
  });

  it('接管收尾动画后连甩:余位方向相反也按甩动方向翻,不许判成往回', () => {
    // 上一把翻到第 5 页、收尾还剩 +0.3 屏余位时又往左甩了 40px:
    // 视觉位移(reach)还是正的,但这一把是明确的向前甩
    expect(swipeTargetPage(5, W * 0.3 - 40, -40, 13, W, -SWIPE_FLING_VELOCITY)).toBe(6);
  });

  it('接管后慢拖看的是视觉位置,不是这一把的手指位移', () => {
    // 余位 +0.2 屏,手指只拖了 -0.1 屏就停住:视觉上没过线,不翻
    expect(swipeTargetPage(5, W * 0.1, -W * 0.1, 13, W, slow)).toBe(5);
    // 余位 +0.2 屏,手指往回拖到视觉位移超过 +0.28 屏:翻回上一页
    expect(swipeTargetPage(5, commit, commit - W * 0.2, 13, W, slow)).toBe(4);
  });

  it('到头翻不动', () => {
    expect(swipeTargetPage(1, 300, 300, 13, W, 2)).toBe(1);
    expect(swipeTargetPage(13, -300, -300, 13, W, -2)).toBe(13);
  });

  it('结果与页码条、跳页夹的是同一个范围', () => {
    for (const dx of [-300, -80, 0, 80, 300]) {
      const target = swipeTargetPage(1, dx, dx, 13, W, slow);
      expect(target).toBe(clampPage(target, 13));
    }
  });

  it('门槛就是屏宽的 SWIPE_COMMIT_RATIO,宽度非法时不塌成 0', () => {
    expect(swipeCommitDistance(W)).toBeCloseTo(W * SWIPE_COMMIT_RATIO);
    expect(swipeCommitDistance(0)).toBeGreaterThan(0);
  });

  it('中段 1:1 跟手,到头才打折', () => {
    expect(swipeOffset(5, 100, 13)).toBe(100);
    expect(swipeOffset(1, 100, 13)).toBe(22);
    expect(swipeOffset(13, -100, 13)).toBe(-22);
  });
});
