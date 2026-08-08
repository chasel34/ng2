import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query';

import { fetchTopicDetail, type TopicDetail } from '@/core/api';

import { fetchNga } from './nga-client';

export interface TopicDetailParams {
  tid: number;
  /** 从 1 起 */
  page: number;
  /** fav 码(CONTEXT.md「fav 码」),从主题列表带进来 */
  favCode?: string;
  /** 只看某人(12 票):服务端按 uid 过滤楼层,分页随之重排 */
  authorId?: number;
}

/**
 * fav 码进 key:带 fav 与不带 fav 请求的是**不同的东西**(隐藏/过期主题只有带码才拿得到),
 * 不区分的话两者会互相命中缓存,从收藏进来的隐藏帖会命中一份空数据。
 * authorId 同理:只看某人的第 N 页与全楼的第 N 页完全是两份数据。
 */
export const topicDetailQueryKey = ({ tid, page, favCode, authorId }: TopicDetailParams) =>
  ['topic-detail', tid, page, favCode ?? null, authorId ?? null] as const;

/**
 * 一页帖子详情。
 *
 * 和主题列表不一样,这里是**按页取**而不是无限滚动:详情页有三种翻页入口
 * (页码条 / 跳页 / 左右滑动),它们共享同一个 `page`,拿页码当 queryKey
 * 才能翻回去时直接命中缓存,而不是又打一次 `read.php`(ADR-0002 的封号风险)。
 *
 * `keepPreviousData` 是给滑动翻页用的:切页时先留着上一页的内容,
 * 免得手指一松整屏先白一下再填上。
 */
export function useTopicDetail(params: TopicDetailParams): UseQueryResult<TopicDetail> {
  const { tid, page, favCode, authorId } = params;

  return useQuery({
    queryKey: topicDetailQueryKey(params),
    queryFn: ({ signal }) =>
      fetchTopicDetail(fetchNga, {
        tid,
        page,
        ...(favCode === undefined ? {} : { favCode }),
        ...(authorId === undefined ? {} : { authorId }),
        signal,
      }),
    placeholderData: keepPreviousData,
    enabled: Number.isFinite(tid) && tid > 0,
  });
}
