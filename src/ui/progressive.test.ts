import { describe, expect, it } from 'vitest';

import { nextRevealCount } from './progressive';

describe('nextRevealCount', () => {
  it('未追平时每步前进 step', () => {
    expect(nextRevealCount(3, 13, 5)).toBe(8);
    expect(nextRevealCount(8, 13, 5)).toBe(13);
  });

  it('最后一步收在 total,不越界', () => {
    expect(nextRevealCount(10, 13, 5)).toBe(13);
  });

  it('已追平(含 total 缩小)时原地不动', () => {
    expect(nextRevealCount(13, 13, 5)).toBe(13);
    // 「只看此人」这类过滤会把列表变短:揭示数不回退,调用方 slice 自然钳到新长度
    expect(nextRevealCount(13, 6, 5)).toBe(13);
  });

  it('空列表不动', () => {
    expect(nextRevealCount(3, 0, 5)).toBe(3);
  });
});
