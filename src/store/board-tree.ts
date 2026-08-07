import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { create } from 'zustand';

import {
  BOARD_TREE_TTL_MS,
  fetchBoardTree,
  loadBoardTree,
  type BoardTreeLoadResult,
  type BoardTreeStore,
  type CachedBoardTree,
} from '@/core/api';

import { fetchNga } from './nga-client';
import { storage } from './storage';

/** 换了缓存结构就换 key,老数据自然作废,不用写迁移。 */
const CACHE_KEY = 'board-tree/v1';

/**
 * core 那个 `BoardTreeStore` 接口的 MMKV 实现——core 层不认识 RN,存储实现只能落在这里。
 *
 * 读出来的一律当外部输入校验:这份 JSON 可能是别的版本的 app 写下的。
 * 读失败返回 null(等价于没缓存)而不是抛——`initialData` 是在 render 期间调的,抛了就白屏。
 */
export const boardTreeStore: BoardTreeStore = {
  read() {
    try {
      const raw = storage.getString(CACHE_KEY);
      if (raw === undefined) return null;
      const parsed = JSON.parse(raw) as CachedBoardTree;
      if (typeof parsed?.fetchedAt !== 'number' || !Array.isArray(parsed?.tree?.categories)) {
        return null;
      }
      return parsed;
    } catch {
      return null;
    }
  },
  write(value) {
    storage.set(CACHE_KEY, JSON.stringify(value));
  },
};

/**
 * 起底用的那份缓存只在冷启动读一次。
 *
 * 整棵树的 JSON 有 100 KB 上下,而 `initialData` 每次 render 都会被调用,
 * 不缓一下就是每帧 parse 一遍。后续更新走 queryFn,不吃这个值。
 */
let initialCache: CachedBoardTree | null | undefined;

function readInitialCache(): CachedBoardTree | null {
  if (initialCache === undefined) initialCache = boardTreeStore.read();
  return initialCache;
}

export const BOARD_TREE_QUERY_KEY = ['board-tree'] as const;

/**
 * 首页的版块分类树。
 *
 * 冷启动先用 MMKV 里的缓存渲染(`initialData`),再由 `loadBoardTree` 按 24 小时节流
 * 决定要不要联网静默更新;断网且有缓存时不报错,结果里带 `error` 供 UI 提示。
 */
export function useBoardTree(): UseQueryResult<BoardTreeLoadResult> {
  return useQuery({
    queryKey: BOARD_TREE_QUERY_KEY,
    queryFn: ({ signal }) =>
      loadBoardTree({
        store: boardTreeStore,
        fetchTree: () => fetchBoardTree(fetchNga, signal),
      }),
    initialData: () => {
      const cached = readInitialCache();
      return cached === null ? undefined : { ...cached, source: 'cache' as const };
    },
    initialDataUpdatedAt: () => readInitialCache()?.fetchedAt,
    staleTime: BOARD_TREE_TTL_MS,
    // 没缓存时才会真的失败,retry 多了只是让错误页迟迟不出来
    retry: 1,
  });
}

interface DismissedAnnouncements {
  ids: readonly string[];
  dismiss: (id: string) => void;
}

const DISMISSED_KEY = 'announcements/dismissed';
/** 只留最近若干条:公告条自带 id,关掉的老公告没必要一直攒着。 */
const DISMISSED_LIMIT = 20;

function loadDismissed(): string[] {
  try {
    const raw = storage.getString(DISMISSED_KEY);
    const parsed: unknown = raw === undefined ? [] : JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

/** 用户关掉过哪些公告。关掉就不该再冒出来,所以要落盘。 */
export const useDismissedAnnouncements = create<DismissedAnnouncements>()((set, get) => ({
  ids: loadDismissed(),
  dismiss: (id) => {
    const ids = [...get().ids.filter((kept) => kept !== id), id].slice(-DISMISSED_LIMIT);
    set({ ids });
    storage.set(DISMISSED_KEY, JSON.stringify(ids));
  },
}));
