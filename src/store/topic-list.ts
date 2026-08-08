import {
  useInfiniteQuery,
  useQueryClient,
  type InfiniteData,
  type UseInfiniteQueryResult,
} from '@tanstack/react-query';
import { useCallback } from 'react';
import { create } from 'zustand';

import { fetchTopicList, type TopicList, type TopicSort } from '@/core/api';

import { fetchNga } from './nga-client';

export interface TopicListParams {
  /** 合集是 stid、普通版块是 fid(CONTEXT.md「合集」) */
  boardId: number;
  kind: 'board' | 'collection';
  sort: TopicSort;
  /** 精华区(recommend=1)。进 queryKey:它和普通列表是两份数据,不能混页 */
  recommend?: boolean;
}

export const topicListQueryKey = ({ boardId, kind, sort, recommend }: TopicListParams) =>
  ['topic-list', kind, boardId, recommend === true ? 'recommend' : sort] as const;

/**
 * 一个版块的主题列表,35 条一页往下翻。
 *
 * 排序进 queryKey:换排序等于换一份数据,不能把两种顺序的页混在一起。
 * 页与页之间会重复(置顶主题每页都回来),去重在 `mergeTopicPages` 里做。
 */
export function useTopicList(
  params: TopicListParams,
): UseInfiniteQueryResult<InfiniteData<TopicList, number>> {
  return useInfiniteQuery({
    queryKey: topicListQueryKey(params),
    queryFn: ({ pageParam, signal }) =>
      fetchTopicList(fetchNga, {
        boardId: params.boardId,
        kind: params.kind,
        page: pageParam,
        sort: params.sort,
        ...(params.recommend === true ? { recommend: true } : {}),
        signal,
      }),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      // 空页 = 到底了(或者被封了),再往下翻只会一直打同一个空响应
      if (lastPage.topics.length === 0) return undefined;
      const next = allPages.length + 1;
      return next > lastPage.totalPages ? undefined : next;
    },
  });
}

/**
 * 下拉刷新:**先把已翻的页砍到只剩第一页,再重取**。
 *
 * 直接 `refetch()` 会把加载过的每一页都重打一遍——翻到第 10 页时下拉一次就是
 * 10 个 `thread.php`,正好撞在 NGA 封第三方客户端的枪口上(ADR-0002)。
 * 而且主题列表按最后回复排序,刷新本来就该回到第一页:后面那些页早就错位了。
 */
export function useRefreshTopicList(params: TopicListParams): () => void {
  const queryClient = useQueryClient();
  const { boardId, kind, sort, recommend } = params;

  return useCallback(() => {
    const queryKey = topicListQueryKey({
      boardId,
      kind,
      sort,
      ...(recommend === true ? { recommend } : {}),
    });
    queryClient.setQueryData<InfiniteData<TopicList, number>>(queryKey, (loaded) =>
      loaded === undefined
        ? loaded
        : { pages: loaded.pages.slice(0, 1), pageParams: loaded.pageParams.slice(0, 1) },
    );
    void queryClient.refetchQueries({ queryKey });
  }, [queryClient, boardId, kind, sort, recommend]);
}

interface SortState {
  sort: TopicSort;
  setSort: (sort: TopicSort) => void;
}

/**
 * 主题列表排序。默认按最后回复(spec §4)。
 *
 * 只活在这次运行里:22 票的设置页要把它做成持久化的设置项,那时再落盘。
 */
export const useTopicSort = create<SortState>()((set) => ({
  sort: 'lastPost',
  setSort: (sort) => set({ sort }),
}));
