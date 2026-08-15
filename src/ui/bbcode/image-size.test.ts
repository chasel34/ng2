import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  attachImageSizePersistence,
  clearImageSizes,
  imageSizeOf,
  rememberImageSize,
  type ImageSize,
} from './image-size';

describe('图片尺寸缓存', () => {
  beforeEach(() => {
    clearImageSizes();
  });

  it('记过的图第二次直接查得到', () => {
    rememberImageSize('a.jpg', { width: 1200, height: 800 });
    expect(imageSizeOf('a.jpg')).toEqual({ width: 1200, height: 800 });
  });

  it('没见过的图返回 undefined', () => {
    expect(imageSizeOf('never.jpg')).toBeUndefined();
  });

  it('缩略图和原图各记各的', () => {
    rememberImageSize('a.jpg', { width: 1200, height: 800 });
    rememberImageSize('a.jpg.thumb.jpg', { width: 120, height: 80 });
    expect(imageSizeOf('a.jpg')?.width).toBe(1200);
    expect(imageSizeOf('a.jpg.thumb.jpg')?.width).toBe(120);
  });

  it('宽高有一边是 0 的不记(解码失败)', () => {
    rememberImageSize('bad.jpg', { width: 0, height: 800 });
    expect(imageSizeOf('bad.jpg')).toBeUndefined();
  });

  it('到上限后丢最早的一条,新的照记', () => {
    for (let index = 0; index < 512; index += 1) {
      rememberImageSize(`img-${index}.jpg`, { width: 100, height: 100 });
    }
    rememberImageSize('newest.jpg', { width: 10, height: 20 });

    expect(imageSizeOf('img-0.jpg')).toBeUndefined();
    expect(imageSizeOf('img-1.jpg')).toBeDefined();
    expect(imageSizeOf('newest.jpg')).toEqual({ width: 10, height: 20 });
  });

  it('重复记同一张不占新坑', () => {
    rememberImageSize('a.jpg', { width: 100, height: 100 });
    for (let index = 0; index < 600; index += 1) {
      rememberImageSize('a.jpg', { width: 200, height: 100 });
    }
    expect(imageSizeOf('a.jpg')).toEqual({ width: 200, height: 100 });
  });
});

describe('持久化挂接', () => {
  beforeEach(() => {
    clearImageSizes();
  });

  it('attach 时回灌存量,本会话已量到的优先', () => {
    rememberImageSize('fresh.jpg', { width: 300, height: 300 });
    attachImageSizePersistence({
      load: () => [
        ['old.jpg', { width: 100, height: 200 }],
        ['fresh.jpg', { width: 1, height: 1 }],
        ['broken.jpg', { width: 0, height: 5 }],
      ],
      save: () => undefined,
    });
    expect(imageSizeOf('old.jpg')).toEqual({ width: 100, height: 200 });
    expect(imageSizeOf('fresh.jpg')).toEqual({ width: 300, height: 300 });
    expect(imageSizeOf('broken.jpg')).toBeUndefined();
  });

  it('记尺寸后延迟合并写回,一秒内多次只写一次', () => {
    vi.useFakeTimers();
    const saved: (readonly [string, ImageSize])[][] = [];
    attachImageSizePersistence({
      load: () => [],
      save: (entries) => saved.push([...entries]),
    });
    rememberImageSize('a.jpg', { width: 10, height: 10 });
    rememberImageSize('b.jpg', { width: 20, height: 20 });
    expect(saved).toHaveLength(0);
    vi.advanceTimersByTime(1100);
    expect(saved).toHaveLength(1);
    expect(saved[0]).toEqual([
      ['a.jpg', { width: 10, height: 10 }],
      ['b.jpg', { width: 20, height: 20 }],
    ]);
    vi.useRealTimers();
  });
});
