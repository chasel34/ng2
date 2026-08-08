import {
  useInfiniteQuery,
  type InfiniteData,
  type UseInfiniteQueryResult,
} from '@tanstack/react-query';

import { fetchUserTopics, hasMoreUserPosts, type TopicList, type UserPostKind } from '@/core/api';

import { fetchNga } from './nga-client';

export interface UserPostsParams {
  uid: number;
  kind: UserPostKind;
}

export const userPostsQueryKey = ({ uid, kind }: UserPostsParams) =>
  ['user-posts', uid, kind] as const;

/**
 * 某人的主题 / 某人的回复,35 条一页往下翻。
 *
 * 翻到底的判据是 `hasMoreUserPosts`(这一页装满了没有)而不是 `totalPages`——
 * 回复列表的 `__ROWS` 是空串,总页数根本算不出来,细节在 core/api/user-topics.ts。
 */
export function useUserPosts(
  params: UserPostsParams,
): UseInfiniteQueryResult<InfiniteData<TopicList, number>> {
  return useInfiniteQuery({
    queryKey: userPostsQueryKey(params),
    queryFn: ({ pageParam, signal }) =>
      fetchUserTopics(fetchNga, {
        uid: params.uid,
        kind: params.kind,
        page: pageParam,
        signal,
      }),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) =>
      hasMoreUserPosts(lastPage) ? allPages.length + 1 : undefined,
    enabled: Number.isFinite(params.uid) && params.uid > 0,
  });
}
