import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { fetchHotTopicPages, mergeTopicPages, type Topic } from '@/core/api';
import { aggregateHotTopics } from '@/core/local';

import { fetchNga } from './nga-client';

/**
 * 一个版块的 24 小时热帖(CONTEXT.md「热帖」——纯本地聚合,不是服务端 API)。
 *
 * 取数(并发拉前几页、失败页容错)在 core/api/hot-topics,
 * 聚合(24h 窗口过滤 + 回复数排序)在 core/local/hot-topics;
 * 这里只负责把两段接起来,并在聚合时把「当前时间」灌进纯函数。
 */

export interface HotTopicsParams {
  /** 合集是 stid、普通版块是 fid(CONTEXT.md「合集」) */
  boardId: number;
  kind: 'board' | 'collection';
}

export interface HotTopicsResult {
  /** 已按热度排好的榜单 */
  topics: Topic[];
  /** 拉失败的页码,非空时 UI 提示「榜单不完整」 */
  failedPages: number[];
  /** 一共试了几页 */
  pagesTried: number;
  /** 榜单算出来的时刻(毫秒),相对时间以它为基准,刷新前不跳动 */
  fetchedAt: number;
}

export const hotTopicsQueryKey = ({ boardId, kind }: HotTopicsParams) =>
  ['hot-topics', kind, boardId] as const;

export function useHotTopics(params: HotTopicsParams): UseQueryResult<HotTopicsResult> {
  return useQuery({
    queryKey: hotTopicsQueryKey(params),
    queryFn: async ({ signal }): Promise<HotTopicsResult> => {
      const result = await fetchHotTopicPages(fetchNga, {
        boardId: params.boardId,
        kind: params.kind,
        signal,
      });
      const fetchedAt = Date.now();
      return {
        // 先按页序去重(mergeTopicPages),再交给纯函数过滤排序;
        // 时间作参数传入,聚合本身不看表
        topics: aggregateHotTopics([mergeTopicPages(result.pages)], {
          now: Math.floor(fetchedAt / 1000),
        }),
        failedPages: [...result.failedPages],
        pagesTried: result.pagesTried,
        fetchedAt,
      };
    },
    // 一次刷新打 5 个 thread.php(ADR-0002 的枪口),别因为切页面就重打
    staleTime: 5 * 60 * 1000,
  });
}
