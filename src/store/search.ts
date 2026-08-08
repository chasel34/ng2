import {
  useInfiniteQuery,
  useQuery,
  type InfiniteData,
  type UseInfiniteQueryResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { create } from 'zustand';

import {
  fetchBoardSearch,
  fetchTopicSearch,
  fetchUserProfile,
  fetchUserProfileByName,
  parseUserSearchInput,
  type BoardSearchItem,
  type TopicList,
  type UserProfile,
} from '@/core/api';

import { fetchNga } from './nga-client';
import { storage } from './storage';

/** 搜索页的三个 tab(设计稿:搜主题 / 搜板块 / 搜用户)。 */
export type SearchTab = 'topics' | 'boards' | 'users';

/** 主题搜索的版块限定(「当前板块」单选)。不带 = 全部板块。 */
export interface SearchBoardScope {
  readonly boardId: number;
  readonly kind: 'board' | 'collection';
  readonly name: string;
}

export interface TopicSearchParams {
  key: string;
  scope?: SearchBoardScope;
  /** 「包括正文」勾选(content=1) */
  content?: boolean;
}

/**
 * 主题搜索,35 条一页往下翻(与版块列表同一形状)。
 * 范围与含正文都进 queryKey:换一种搜法就是另一份数据,不能混页。
 */
export function useTopicSearch(
  params: TopicSearchParams,
): UseInfiniteQueryResult<InfiniteData<TopicList, number>> {
  const { key, scope, content } = params;
  return useInfiniteQuery({
    queryKey: [
      'search',
      'topics',
      key,
      scope?.kind ?? 'all',
      scope?.boardId ?? 0,
      content === true,
    ],
    queryFn: ({ pageParam, signal }) =>
      fetchTopicSearch(fetchNga, {
        key,
        page: pageParam,
        ...(scope === undefined ? {} : { boardId: scope.boardId, kind: scope.kind }),
        ...(content === true ? { searchContent: true } : {}),
        signal,
      }),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      // 空页 = 到底了(没有结果/翻过头都归一成空页),别再打同一个空响应
      if (lastPage.topics.length === 0) return undefined;
      const next = allPages.length + 1;
      return next > lastPage.totalPages ? undefined : next;
    },
    enabled: key !== '',
  });
}

/** 版块搜索。一次给全量(实测上限 100 条),没有分页。 */
export function useBoardSearch(key: string): UseQueryResult<BoardSearchItem[]> {
  return useQuery({
    queryKey: ['search', 'boards', key],
    queryFn: ({ signal }) => fetchBoardSearch(fetchNga, { key, signal }),
    enabled: key !== '',
  });
}

/**
 * 用户搜索:纯数字按 uid、否则按用户名走 ucp 资料查询(core/api/search.ts)。
 * 查无此人是 server 错误(「找不到用户」),落在 error 上由结果页措辞。
 */
export function useUserSearch(key: string): UseQueryResult<UserProfile> {
  const query = parseUserSearchInput(key);
  return useQuery({
    queryKey: ['search', 'users', key],
    queryFn: ({ signal }) =>
      query?.kind === 'uid'
        ? fetchUserProfile(fetchNga, { uid: query.uid, signal })
        : fetchUserProfileByName(fetchNga, { username: query?.username ?? key, signal }),
    enabled: query !== undefined,
    // 与 useUserProfile 同理:资料不常变,反复搜同一个人不该反复打 ucp(ADR-0002)
    staleTime: 5 * 60 * 1000,
  });
}

/** 一条搜索历史:词 + 当时的范围(主题 tab 才有 scope/content,重搜时原样还原)。 */
export interface SearchHistoryEntry {
  readonly query: string;
  readonly scope?: SearchBoardScope;
  readonly content?: boolean;
}

interface SearchHistoryState {
  /** 各 tab 独立(设计稿:三个 tab 各自的搜索历史),新搜的在前 */
  readonly byTab: Readonly<Record<SearchTab, readonly SearchHistoryEntry[]>>;
  add: (tab: SearchTab, entry: SearchHistoryEntry) => void;
  remove: (tab: SearchTab, index: number) => void;
  clear: (tab: SearchTab) => void;
}

const HISTORY_KEY = 'search/history';
/** 每个 tab 各留最近若干条,设计稿一屏画了 6 条,20 足够翻一翻。 */
const HISTORY_LIMIT = 20;

const EMPTY_HISTORY: Record<SearchTab, readonly SearchHistoryEntry[]> = {
  topics: [],
  boards: [],
  users: [],
};

function loadHistory(): Record<SearchTab, readonly SearchHistoryEntry[]> {
  try {
    const raw = storage.getString(HISTORY_KEY);
    if (raw === undefined) return EMPTY_HISTORY;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return EMPTY_HISTORY;
    const record = parsed as Record<string, unknown>;
    const listOf = (tab: SearchTab): readonly SearchHistoryEntry[] => {
      const list = record[tab];
      if (!Array.isArray(list)) return [];
      return list.filter(
        (item): item is SearchHistoryEntry =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as { query?: unknown }).query === 'string',
      );
    };
    return { topics: listOf('topics'), boards: listOf('boards'), users: listOf('users') };
  } catch {
    return EMPTY_HISTORY;
  }
}

/** 同一个词同一种范围只留一条:重搜挪到最前,不同范围算不同历史(标注不一样)。 */
const sameEntry = (a: SearchHistoryEntry, b: SearchHistoryEntry): boolean =>
  a.query === b.query &&
  a.scope?.boardId === b.scope?.boardId &&
  (a.content === true) === (b.content === true);

/** 各 tab 独立的搜索历史,MMKV 持久化(spec §4:小数据走 MMKV)。 */
export const useSearchHistory = create<SearchHistoryState>()((set, get) => {
  const persist = (byTab: SearchHistoryState['byTab']) => {
    set({ byTab });
    storage.set(HISTORY_KEY, JSON.stringify(byTab));
  };
  return {
    byTab: loadHistory(),
    add: (tab, entry) => {
      const kept = get().byTab[tab].filter((item) => !sameEntry(item, entry));
      persist({ ...get().byTab, [tab]: [entry, ...kept].slice(0, HISTORY_LIMIT) });
    },
    remove: (tab, index) => {
      persist({ ...get().byTab, [tab]: get().byTab[tab].filter((_, i) => i !== index) });
    },
    clear: (tab) => {
      persist({ ...get().byTab, [tab]: [] });
    },
  };
});
