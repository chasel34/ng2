import { create } from 'zustand';

import { fetchTopicDetail } from '@/core/api';

import { fetchNga } from './nga-client';
import { saveCachedPage } from './topic-cache';

/**
 * 手动缓存(详情页 ⋮ 的「缓存本页 / 缓存整帖」)。
 *
 * 自动缓存是「读到哪存到哪」,这里是**主动把整帖顺序拉一遍**。两件事必须做到:
 *
 * - **限速**:整帖就是几十上百次 `read.php`,连着打是最容易被封的行为(ADR-0002),
 *   所以每页之间隔一会儿再发下一页;
 * - **可中断**:用户随时能停,停了已经缓存的页照样留着。
 *
 * 页面本身不参与:写盘走 `fetchTopicDetail` 的 onSnapshot,与浏览时那条路完全一样。
 */

/** 两页之间的间隔。整帖缓存是本 app 唯一会连续打 read.php 的地方,宁可慢。 */
const PAGE_INTERVAL_MS = 800;

export type CacheDownloadOutcome =
  | { readonly kind: 'done'; readonly cached: number }
  /** 用户按了取消;`cached` 是已经存下的页数 */
  | { readonly kind: 'cancelled'; readonly cached: number }
  /** 某一页拉失败就停手(接着打大概率是被封了),已存的页保留 */
  | { readonly kind: 'failed'; readonly cached: number; readonly message: string }
  /** 上一趟还没跑完 */
  | { readonly kind: 'busy' };

interface CacheDownloadState {
  /** 正在缓存的主题;null = 空闲 */
  readonly tid: number | null;
  readonly done: number;
  readonly total: number;
}

const IDLE: CacheDownloadState = { tid: null, done: 0, total: 0 };

export const useCacheDownload = create<CacheDownloadState>()(() => IDLE);

/** 进度条要的东西;空闲时 tid 为 null。 */
export const useCacheDownloadProgress = (): CacheDownloadState =>
  useCacheDownload((state) => state);

let controller: AbortController | undefined;

export interface CacheTopicOptions {
  readonly tid: number;
  /** 要缓存的页码,升序;「缓存本页」就是只有一项的数组 */
  readonly pages: readonly number[];
  readonly favCode?: string;
}

/** 中断正在跑的整帖缓存。 */
export function cancelTopicCacheDownload(): void {
  controller?.abort();
}

/**
 * 顺序缓存指定的几页。同一时刻只允许一趟(第二次调用直接回 `busy`)——
 * 两趟并行就是两倍的 read.php 频次。
 */
export async function cacheTopicPages(options: CacheTopicOptions): Promise<CacheDownloadOutcome> {
  const { tid, pages, favCode } = options;
  if (useCacheDownload.getState().tid !== null) return { kind: 'busy' };
  if (pages.length === 0) return { kind: 'done', cached: 0 };

  const abort = new AbortController();
  controller = abort;
  useCacheDownload.setState({ tid, done: 0, total: pages.length });

  let cached = 0;
  try {
    for (const [index, page] of pages.entries()) {
      if (abort.signal.aborted) return { kind: 'cancelled', cached };
      // 第一页立刻发:「缓存本页」就该是按下去马上有反应
      if (index > 0) {
        await sleep(PAGE_INTERVAL_MS, abort.signal);
        if (abort.signal.aborted) return { kind: 'cancelled', cached };
      }

      try {
        await fetchTopicDetail(fetchNga, {
          tid,
          page,
          ...(favCode === undefined ? {} : { favCode }),
          signal: abort.signal,
          onSnapshot: saveCachedPage,
        });
      } catch (cause) {
        if (abort.signal.aborted) return { kind: 'cancelled', cached };
        return {
          kind: 'failed',
          cached,
          message: cause instanceof Error ? cause.message : '缓存失败',
        };
      }

      cached += 1;
      useCacheDownload.setState({ done: cached });
    }
    return { kind: 'done', cached };
  } finally {
    controller = undefined;
    useCacheDownload.setState(IDLE);
  }
}

/** 可中断的等待:取消时立刻醒,不必等这一格走完。 */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      resolve();
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}
