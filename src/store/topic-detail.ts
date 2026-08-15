import {
  keepPreviousData,
  useQuery,
  type QueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import { fetchTopicDetail, type TopicDetail } from '@/core/api';

import { fetchNga } from './nga-client';
import { deferCachedPage } from './topic-cache';

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
 * 一页帖子详情在缓存里算「新鲜」多久。
 *
 * 全局默认是 0(每次挂载都重打),对 `read.php` 这条路太激进了:退回主题列表再点进
 * 同一个帖、从回复链页返回、深链来回跳——这些都是几秒内的事,内容不可能变,
 * 却各打一发 NGA(ADR-0002:被封是常态,少打一发就少一分风险)。
 *
 * 2 分钟这一档是按「用户觉得内容该更新了没有」定的:更长会让人退出去再进来还看到
 * 旧的一页(论坛帖几分钟内多几楼是常事);更短就吃不掉「来回进出」这个最常见的模式。
 *
 * 它**不会**挡住任何显式刷新——下拉刷新、FAB 的「刷新」、提示条的「重试原生」走的都是
 * `refetch()`,`refetch` 无视 `staleTime`,一定真发请求。翻到没读过的页也一定发
 * (新 queryKey 没有缓存条目)。被吃掉的只有「重新挂载一个已经在缓存里的页」。
 */
const TOPIC_DETAIL_STALE_MS = 2 * 60_000;

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
        deferSnapshot: deferCachedPage,
      }),
    placeholderData: keepPreviousData,
    staleTime: TOPIC_DETAIL_STALE_MS,
    enabled: Number.isFinite(tid) && tid > 0,
  });
}
