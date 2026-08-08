import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import {
  addBoardFavorite,
  clearBoardFavorites,
  fetchBoardFavorites,
  removeBoardFavorite,
  type Board,
} from '@/core/api';

import { useAccounts } from './accounts';
import { fetchNga } from './nga-client';

/**
 * 版块收藏(CONTEXT.md「版块收藏」)的取数与写操作。
 *
 * 缓存 key 按账号分桶:收藏是账号级数据,切换账号后不能拿别人的列表充数。
 * 写操作全部乐观更新——设计稿的星标是「点了立刻变」,等一个来回的网络太钝;
 * 失败回滚并把服务端的话抛给调用方去 toast,成功后再 invalidate 与服务端对齐
 * (手动输 id 添加的版块,名字与合集身份要靠重拉列表才能拿到)。
 */
export const boardFavoritesKey = (uid: string | null) =>
  ['board-favorites', uid ?? 'guest'] as const;

/** 云端收藏列表。游客不发请求(接口会报「你必须先登录论坛」),data 一直是 undefined。 */
export function useBoardFavorites(): UseQueryResult<Board[]> {
  const uid = useAccounts((state) => state.currentUid);
  return useQuery({
    queryKey: boardFavoritesKey(uid),
    queryFn: ({ signal }) => fetchBoardFavorites(fetchNga, signal),
    enabled: uid !== null,
    // 列表页的星标每次进版块都要问一次「收了没」,不缓存就是每开一个版块多打一次
    // 接口(ADR-0002:能少打就少打)。改动都走 invalidate,不靠这个 TTL 保新。
    staleTime: 5 * 60 * 1000,
  });
}

/** 某个版块当前是否已收藏。列表还没拉回来时按「未收藏」画,回来后自动纠正。 */
export function useIsBoardFavored(boardId: number): boolean {
  const { data } = useBoardFavorites();
  return (data ?? []).some((board) => board.id === boardId);
}

export interface BoardFavoriteMutations {
  /** 收藏(乐观插到最前——服务端就是新收藏在前的顺序) */
  add: (board: Board) => Promise<void>;
  /** 取消收藏(乐观移除) */
  remove: (board: Board) => Promise<void>;
  /** 清空,返回删掉的列表给「撤销」用 */
  clear: () => Promise<Board[]>;
  /** 撤销清空:把删掉的列表按原顺序收回来 */
  restore: (boards: readonly Board[]) => Promise<void>;
}

export function useBoardFavoriteMutations(): BoardFavoriteMutations {
  const uid = useAccounts((state) => state.currentUid);
  const client = useQueryClient();
  const key = boardFavoritesKey(uid);

  const settle = () => client.invalidateQueries({ queryKey: key });
  const snapshot = () => {
    const previous = client.getQueryData<Board[]>(key);
    return { previous };
  };
  const rollback = (context: { previous?: Board[] | undefined } | undefined) => {
    if (context?.previous !== undefined) client.setQueryData(key, context.previous);
  };

  const add = useMutation({
    mutationFn: (board: Board) => addBoardFavorite(fetchNga, board.id),
    onMutate: (board) => {
      const context = snapshot();
      client.setQueryData<Board[]>(key, (old = []) => [
        board,
        ...old.filter((item) => item.id !== board.id),
      ]);
      return context;
    },
    onError: (_error, _board, context) => rollback(context),
    onSettled: settle,
  });

  const remove = useMutation({
    mutationFn: (board: Board) => removeBoardFavorite(fetchNga, board.id),
    onMutate: (board) => {
      const context = snapshot();
      client.setQueryData<Board[]>(key, (old = []) =>
        old.filter((item) => item.id !== board.id),
      );
      return context;
    },
    onError: (_error, _board, context) => rollback(context),
    onSettled: settle,
  });

  const clear = useMutation({
    mutationFn: () => clearBoardFavorites(fetchNga),
    onMutate: () => {
      const context = snapshot();
      client.setQueryData<Board[]>(key, []);
      return context;
    },
    onError: (_error, _variables, context) => rollback(context),
    onSettled: settle,
  });

  const restore = useMutation({
    // 逐个收回;顺序反着加,服务端「新收藏在前」正好还原原顺序
    mutationFn: async (boards: readonly Board[]) => {
      for (const board of [...boards].reverse()) {
        await addBoardFavorite(fetchNga, board.id);
      }
    },
    onMutate: (boards) => {
      const context = snapshot();
      client.setQueryData<Board[]>(key, [...boards]);
      return context;
    },
    onError: (_error, _boards, context) => rollback(context),
    onSettled: settle,
  });

  return {
    add: (board) => add.mutateAsync(board).then(() => undefined),
    remove: (board) => remove.mutateAsync(board).then(() => undefined),
    clear: () => clear.mutateAsync(),
    restore: (boards) => restore.mutateAsync(boards).then(() => undefined),
  };
}

/**
 * 「添加版面 ID」:先按输入乐观收藏,成功后从重拉的列表里找回这个版块——
 * 输入到底是 fid 还是 stid(合集)由服务端识别,列表条目带 stid 就是合集,
 * 名字也以服务端为准。找不到(理论上不会)就退回占位对象。
 */
export function useAddBoardFavoriteById(): (id: number, provisional: Board) => Promise<Board> {
  const uid = useAccounts((state) => state.currentUid);
  const client = useQueryClient();
  const { add } = useBoardFavoriteMutations();

  return async (id, provisional) => {
    await add(provisional);
    const fresh = await client.fetchQuery({
      queryKey: boardFavoritesKey(uid),
      queryFn: ({ signal }) => fetchBoardFavorites(fetchNga, signal),
    });
    return (
      fresh.find((board) => board.id === id || board.fid === id || board.stid === id) ??
      provisional
    );
  };
}
