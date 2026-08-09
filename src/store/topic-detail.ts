import {
  keepPreviousData,
  useQuery,
  type QueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import { fetchTopicDetail, type TopicDetail } from '@/core/api';

import { fetchNga } from './nga-client';
import { saveCachedPage } from './topic-cache';

export interface TopicDetailParams {
  tid: number;
  /** 从 1 起 */
  page: number;
  /** fav 码(CONTEXT.md「fav 码」),从主题列表带进来 */
  favCode?: string;
  /**
   * 只看某一楼(14 的「我的回复」、24 的 pid 深链):服务端把那一楼单独捞出来,
   * 响应里只有这一条楼层。和 authorId 一样必须进 queryKey——
   * 它与整帖第 1 页是两份完全不同的数据。
   */
  pid?: number;
  /** 只看某人(12 票):服务端按 uid 过滤楼层,分页随之重排 */
  authorId?: number;
}

/**
 * fav 码进 key:带 fav 与不带 fav 请求的是**不同的东西**(隐藏/过期主题只有带码才拿得到),
 * 不区分的话两者会互相命中缓存,从收藏进来的隐藏帖会命中一份空数据。
 * pid / authorId 同理:只看某一楼、只看某人的第 N 页与全楼的第 N 页完全是两份数据。
 */
export const topicDetailQueryKey = ({ tid, page, favCode, pid, authorId }: TopicDetailParams) =>
  ['topic-detail', tid, page, favCode ?? null, pid ?? null, authorId ?? null] as const;

/**
 * 这个主题已经进了 Query 缓存的**完整页**(26 票的「本帖已加载楼层」口径)。
 *
 * 只认 pid/authorId 都空的整页——只看该楼/只看某人是过滤视图,楼号与分页
 * 都是过滤后的口径,混进 quote 索引会指错楼。fav 码要对上:带码与不带码
 * 拿到的可能根本不是同一份数据。
 */
export function loadedTopicPages(
  queryClient: QueryClient,
  tid: number,
  favCode?: string,
): readonly TopicDetail[] {
  return queryClient
    .getQueriesData<TopicDetail>({ queryKey: ['topic-detail', tid] })
    .filter(
      ([key]) => key[3] === (favCode ?? null) && key[4] === null && key[5] === null,
    )
    .map(([, detail]) => detail)
    .filter((detail): detail is TopicDetail => detail !== undefined)
    .sort((a, b) => a.page - b.page);
}

/**
 * 一页帖子详情。
 *
 * 和主题列表不一样,这里是**按页取**而不是无限滚动:详情页有三种翻页入口
 * (页码条 / 跳页 / 左右滑动),它们共享同一个 `page`,拿页码当 queryKey
 * 才能翻回去时直接命中缓存,而不是又打一次 `read.php`(ADR-0002 的封号风险)。
 *
 * `keepPreviousData` 是给滑动翻页用的:切页时先留着上一页的内容,
 * 免得手指一松整屏先白一下再填上。
 *
 * `onSnapshot` 是 20 票的自动缓存:浏览过的整帖页顺手写进 SQLite,
 * 断网时反封锁链的缓存档就是从那儿把这一页还回来的(过滤视图 core 层会挡掉)。
 */
export function useTopicDetail(params: TopicDetailParams): UseQueryResult<TopicDetail> {
  const { tid, page, favCode, pid, authorId } = params;

  return useQuery({
    queryKey: topicDetailQueryKey(params),
    queryFn: ({ signal }) =>
      fetchTopicDetail(fetchNga, {
        tid,
        page,
        ...(favCode === undefined ? {} : { favCode }),
        ...(pid === undefined ? {} : { pid }),
        ...(authorId === undefined ? {} : { authorId }),
        signal,
        onSnapshot: saveCachedPage,
      }),
    placeholderData: keepPreviousData,
    enabled: Number.isFinite(tid) && tid > 0,
  });
}
