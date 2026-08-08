import { useCallback, useRef, useState } from 'react';

import {
  expectedRecommendDelta,
  nextRecommendState,
  postRecommend,
  type Floor,
  type RecommendAction,
  type RecommendMark,
  type RecommendState,
} from '@/core/api';

import { fetchNga } from './nga-client';

/** 一次赞踩落定后的结果,给 UI 决定 toast 文案。 */
export interface RecommendOutcome {
  readonly action: RecommendAction;
  readonly state: RecommendState;
}

/**
 * 赞踩接口要的 pid:**主楼必须传 0**(API 文档 §6),回复楼层用真 pid。
 * 本地标记也按这个值做 key,卡片与菜单读写的才是同一份状态。
 */
export const recommendPidOf = (floor: Floor): number => (floor.lou === 0 ? 0 : floor.pid);

/**
 * 一个主题内各楼层的赞踩状态(12 票)。
 *
 * 服务端不下发「我赞过没有」,状态只能从本会话的操作里长出来——挂在详情页的
 * 组件 state 上,翻页/只看此人期间都还在,离开主题即弃。
 *
 * 乐观更新:点下去先按预测迁移(core 纯函数)变色变数,请求回来再用服务端
 * delta 校正;失败回滚到点击前的样子。同一楼层的请求在途时按第二次是 no-op,
 * 免得两次切换互相踩(NGA 的赞踩没有幂等)。
 */
export function useFloorRecommend(tid: number) {
  const [marks, setMarks] = useState<Readonly<Record<number, RecommendMark>>>({});
  const pendingRef = useRef(new Set<number>());

  const markOf = useCallback((pid: number): RecommendMark | undefined => marks[pid], [marks]);

  const toggle = useCallback(
    async (pid: number, action: RecommendAction): Promise<RecommendOutcome | undefined> => {
      if (pendingRef.current.has(pid)) return undefined;
      pendingRef.current.add(pid);

      const before: RecommendMark = marks[pid] ?? { state: 'none', scoreDelta: 0 };
      // 乐观:预测迁移先上屏
      setMarks((current) => ({
        ...current,
        [pid]: {
          state: nextRecommendState(before.state, action),
          scoreDelta: before.scoreDelta + expectedRecommendDelta(before.state, action),
        },
      }));

      try {
        // 最终状态以服务端 delta 为准——预测错了(比如别处已赞过)这里会拧回来
        const result = await postRecommend(fetchNga, { tid, pid, action });
        const settled: RecommendMark = {
          state: result.state,
          scoreDelta: before.scoreDelta + result.delta,
        };
        setMarks((current) => ({ ...current, [pid]: settled }));
        return { action, state: settled.state };
      } catch (error) {
        // 失败回滚到点击前
        setMarks((current) => ({ ...current, [pid]: before }));
        throw error;
      } finally {
        pendingRef.current.delete(pid);
      }
    },
    [tid, marks],
  );

  return { markOf, toggle };
}
