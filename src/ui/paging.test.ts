import { describe, expect, it } from 'vitest';

import {
  SWIPE_COMMIT_DISTANCE,
  SWIPE_HINT_DISTANCE,
  clampPage,
  parseJumpTarget,
  swipeHintText,
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
  it('往左划下一页、往右划上一页', () => {
    expect(swipeTargetPage(5, -100, 13)).toBe(6);
    expect(swipeTargetPage(5, 100, 13)).toBe(4);
  });

  it('没走够阈值就不翻', () => {
    expect(swipeTargetPage(5, -SWIPE_COMMIT_DISTANCE, 13)).toBe(5);
    expect(swipeTargetPage(5, 10, 13)).toBe(5);
  });

  it('到头翻不动', () => {
    expect(swipeTargetPage(1, 200, 13)).toBe(1);
    expect(swipeTargetPage(13, -200, 13)).toBe(13);
  });

  it('结果与页码条、跳页夹的是同一个范围', () => {
    for (const dx of [-300, -80, 0, 80, 300]) {
      const target = swipeTargetPage(1, dx, 13);
      expect(target).toBe(clampPage(target, 13));
    }
  });

  it('提示文案:够远才出,到头明说', () => {
    expect(swipeHintText(5, -10, 13)).toBeUndefined();
    expect(swipeHintText(5, -SWIPE_HINT_DISTANCE, 13)).toBeUndefined();
    expect(swipeHintText(5, -50, 13)).toBe('第 6 页');
    expect(swipeHintText(5, 50, 13)).toBe('第 4 页');
    expect(swipeHintText(1, 50, 13)).toBe('已是第一页');
    expect(swipeHintText(13, -50, 13)).toBe('已是最后一页');
  });

  it('到头时内容跟手的距离明显变小(阻尼)', () => {
    expect(swipeOffset(5, 100, 13)).toBe(70);
    expect(swipeOffset(1, 100, 13)).toBe(25);
    expect(swipeOffset(13, -100, 13)).toBe(-25);
  });
});
