import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type UseInfiniteQueryResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useCallback } from 'react';
import { create } from 'zustand';

import {
  addTopicFavorite,
  createFavoriteFolder,
  deleteFavoriteFolder,
  fetchFavoriteFolders,
  fetchFavoriteTopics,
  modifyFavoriteFolder,
  removeTopicFavorite,
  type FavoriteFolder,
  type TopicList,
} from '@/core/api';
import {
  EMPTY_TOPIC_FAVOR_INDEX,
  applyFavoriteChange,
  foldersOfTopic,
  parseTopicFavorIndex,
  pruneFolders,
  seedFolderTopics,
  type FavoriteChange,
  type SeedFolderTopicsOptions,
  type TopicFavorIndex,
} from '@/core/local';

import { currentAccount, useAccounts } from './accounts';
import { fetchNga } from './nga-client';
import { storage } from './storage';

export const FAVORITE_FOLDERS_QUERY_KEY = ['favorite-folders'] as const;

export const favoriteTopicsQueryKey = (folderId: number) =>
  ['favorite-topics', folderId] as const;

/** 换了存储结构就换 key,老数据自然作废,不用写迁移。 */
const INDEX_KEY_PREFIX = 'topic-favor-index/v1/';

/**
 * 归属索引按账号分开存:收藏夹是云端按账号分的,两个账号的夹 id 混在一起会串。
 * 游客态没有收藏夹,索引恒为空。
 */
const indexKeyOf = (uid: string | null): string | null =>
  uid === null ? null : `${INDEX_KEY_PREFIX}${uid}`;

function loadIndex(uid: string | null): TopicFavorIndex {
  const key = indexKeyOf(uid);
  if (key === null) return EMPTY_TOPIC_FAVOR_INDEX;
  try {
    const raw = storage.getString(key);
    return raw === undefined ? EMPTY_TOPIC_FAVOR_INDEX : parseTopicFavorIndex(JSON.parse(raw));
  } catch {
    // 存储坏了就当没记过——索引本来就是「宁缺勿滥」的,重新攒即可
    return EMPTY_TOPIC_FAVOR_INDEX;
  }
}

interface TopicFavorIndexStore {
  /** 索引属于哪个账号;切号后由 `sync` 换掉 */
  uid: string | null;
  index: TopicFavorIndex;
  sync: (uid: string | null) => void;
  applyChange: (change: FavoriteChange) => void;
  seedFolder: (options: SeedFolderTopicsOptions) => void;
  prune: (folderIds: readonly number[]) => void;
}

/**
 * 「主题在哪几个收藏夹里」的本机索引(core/local 那套纯函数的落地)。
 * 服务端给不出这个反向关系,只能本机攒——为什么这么设计见 core/local/topic-favor-index.ts。
 */
export const useTopicFavorIndex = create<TopicFavorIndexStore>()((set, get) => {
  const update = (next: TopicFavorIndex) => {
    const { uid, index } = get();
    if (next === index) return;
    set({ index: next });
    const key = indexKeyOf(uid);
    if (key === null) return;
    try {
      storage.set(key, JSON.stringify(next));
    } catch {
      // 写不进就只活在内存,功能照常
    }
  };

  const initialUid = currentAccount()?.uid ?? null;
  return {
    uid: initialUid,
    index: loadIndex(initialUid),
    sync: (uid) => {
      if (get().uid === uid) return;
      set({ uid, index: loadIndex(uid) });
    },
    applyChange: (change) => update(applyFavoriteChange(get().index, change)),
    seedFolder: (options) => update(seedFolderTopics(get().index, options)),
    prune: (folderIds) => update(pruneFolders(get().index, folderIds)),
  };
});

// 切号/退出登录后自动换到那个账号的索引
useAccounts.subscribe(() => {
  useTopicFavorIndex.getState().sync(currentAccount()?.uid ?? null);
});

/** 本机已知的、这个主题所属的收藏夹 id。多选对话框拿它当初始勾选。 */
export function useTopicFolderIds(tid: number): readonly number[] {
  return useTopicFavorIndex((state) => foldersOfTopic(state.index, tid));
}

/** 云端收藏夹列表。写操作成功后一律重拉它,列表以服务端为准(11 票验收项)。 */
export function useFavoriteFolders(): UseQueryResult<FavoriteFolder[]> {
  return useQuery({
    queryKey: FAVORITE_FOLDERS_QUERY_KEY,
    queryFn: ({ signal }) => fetchFavoriteFolders(fetchNga, signal),
  });
}

/**
 * 某个收藏夹里的主题,35 条一页往下翻(`thread.php?favor=<夹id>`)。
 *
 * 翻到的每一页顺手喂给归属索引:用户逛过的收藏夹,下次开多选对话框就勾得准。
 * `folderId` 为 undefined(夹列表还没回来)时不发请求。
 */
export function useFavoriteTopics(
  folderId: number | undefined,
): UseInfiniteQueryResult<InfiniteData<TopicList, number>> {
  const seedFolder = useTopicFavorIndex((state) => state.seedFolder);
  // enabled 为假时 queryFn 不会跑,占位的 0 只是让 queryKey 有个形状
  const id = folderId ?? 0;

  return useInfiniteQuery({
    queryKey: favoriteTopicsQueryKey(id),
    enabled: folderId !== undefined,
    queryFn: async ({ pageParam, signal }) => {
      const list = await fetchFavoriteTopics(fetchNga, { folderId: id, page: pageParam, signal });
      seedFolder({
        folderId: id,
        tids: list.topics.map((topic) => topic.tid),
        // 整个夹就这一页时才敢反过来清本机记错的归属
        complete: pageParam === 1 && list.totalPages <= 1,
      });
      return list;
    },
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      if (lastPage.topics.length === 0) return undefined;
      const next = allPages.length + 1;
      return next > lastPage.totalPages ? undefined : next;
    },
  });
}

/**
 * 下拉刷新:**砍到只剩第一页再重取**,与主题列表同一套理由——
 * 直接 refetch 会把翻过的每一页都重打一遍,翻到第 10 页就是 10 个 `thread.php`(ADR-0002)。
 * `resetQueries` 正好是「丢掉已翻的页、活着的查询重取第一页」。
 */
export function useRefreshFavoriteTopics(folderId: number | undefined): () => void {
  const queryClient = useQueryClient();
  return useCallback(() => {
    if (folderId === undefined) return;
    void queryClient.resetQueries({ queryKey: favoriteTopicsQueryKey(folderId) });
  }, [queryClient, folderId]);
}

/**
 * 收藏夹增删改共用的善后:夹列表必重拉(计数与默认徽标一律以服务端为准),
 * 受影响的那几个夹的主题列表连同已翻的页一起丢掉,下次进去从第一页重取。
 */
function useAfterFolderChange() {
  const queryClient = useQueryClient();
  return useCallback(
    async (...folderIds: readonly number[]) => {
      for (const folderId of folderIds) {
        void queryClient.resetQueries({ queryKey: favoriteTopicsQueryKey(folderId) });
      }
      await queryClient.invalidateQueries({ queryKey: FAVORITE_FOLDERS_QUERY_KEY });
    },
    [queryClient],
  );
}

export interface CreateFolderInput {
  readonly name: string;
  readonly asDefault?: boolean;
}

/** 新建收藏夹。返回新夹 id(服务端没给就是 undefined,调用方反正读重拉的列表)。 */
export function useCreateFolder() {
  const afterChange = useAfterFolderChange();
  return useMutation({
    mutationFn: (input: CreateFolderInput) => createFavoriteFolder(fetchNga, input),
    onSuccess: () => afterChange(),
  });
}

export interface ModifyFolderInput {
  readonly folderId: number;
  /** 重命名传新名;只设默认时传夹的现名(服务端要求 name 必传) */
  readonly name: string;
  readonly asDefault?: boolean;
}

/** 重命名 / 设为默认(服务端是同一个 `modify_folder`)。 */
export function useModifyFolder() {
  const afterChange = useAfterFolderChange();
  return useMutation({
    mutationFn: (input: ModifyFolderInput) => modifyFavoriteFolder(fetchNga, input),
    onSuccess: () => afterChange(),
  });
}

/** 删除收藏夹。夹里的收藏一并没了,所以本机索引也要把这个夹摘干净。 */
export function useDeleteFolder() {
  const afterChange = useAfterFolderChange();
  const prune = useTopicFavorIndex((state) => state.prune);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (folderId: number) => deleteFavoriteFolder(fetchNga, { folderId }),
    onSuccess: async (_result, folderId) => {
      await afterChange(folderId);
      // 以重拉回来的服务端列表为准,把已经不存在的夹从索引里摘掉
      const folders = queryClient.getQueryData<FavoriteFolder[]>(FAVORITE_FOLDERS_QUERY_KEY);
      if (folders !== undefined) prune(folders.map((folder) => folder.id));
    },
  });
}

export interface ApplyTopicFavoritesInput {
  readonly tid: number;
  /** 要加进的夹 */
  readonly added: readonly number[];
  /** 要移出的夹 */
  readonly removed: readonly number[];
}

/**
 * 多选对话框点「完成」:把勾选的差异逐个落到服务端。
 *
 * **逐个串行发**,不并发:一次勾三四个夹就是三四个 `nuke.php`,
 * 并发打过去正撞在 NGA 封第三方客户端的枪口上(ADR-0002)。
 * 每成功一个就更新一次本机索引——中途失败时,已经做成的那几个不会被回滚掉。
 */
export function useApplyTopicFavorites() {
  const afterChange = useAfterFolderChange();
  const applyChange = useTopicFavorIndex((state) => state.applyChange);

  return useMutation({
    mutationFn: async ({ tid, added, removed }: ApplyTopicFavoritesInput) => {
      for (const folderId of added) {
        await addTopicFavorite(fetchNga, { tid, folderId });
        applyChange({ tid, folderId, favored: true });
      }
      for (const folderId of removed) {
        await removeTopicFavorite(fetchNga, { tid, folderId });
        applyChange({ tid, folderId, favored: false });
      }
    },
    // 成功失败都要善后:失败也可能是做了一半,夹里的计数已经变了
    onSettled: (_result, _error, { added, removed }) => afterChange(...added, ...removed),
  });
}
