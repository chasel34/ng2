import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { create } from 'zustand';

import {
  EMPTY_BLOCK_WORDS,
  fetchBlockWords,
  officialFilterRules,
  setBlockWords,
  type BlockWordList,
  type BlockedUser,
  type Floor,
  type FloorUser,
  type Topic,
} from '@/core/api';
import {
  createFilterRule,
  matchFilterRules,
  removeFilterRule,
  upsertFilterRule,
  type FilterRule,
  type FilterRuleInput,
} from '@/core/local';

import { useAccounts } from './accounts';
import { fetchNga } from './nga-client';
import { storage } from './storage';

/**
 * 屏蔽规则(CONTEXT.md「屏蔽规则」)的两份数据源:
 *
 * - **本地规则**:MMKV 持久化(spec §4:小数据走 MMKV),游客也能用,卸载即丢;
 * - **官方屏蔽词**:NGA 账号云端那份(API 文档 §11.5),走 TanStack Query 按账号分桶。
 *
 * 判定统一走 `core/local` 的 `matchFilterRules`——两份规则在这里拼成一张表,
 * 本地在前:折叠行报的是用户自己加的那条,点「解除」才落在他能删的规则上。
 */

const RULES_KEY = 'filters/local-rules';

interface LocalFilterState {
  readonly rules: readonly FilterRule[];
  add: (input: FilterRuleInput) => FilterRule;
  remove: (id: string) => void;
  restore: (rule: FilterRule) => void;
}

function loadRules(): readonly FilterRule[] {
  try {
    const raw = storage.getString(RULES_KEY);
    if (raw === undefined) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // 存过的结构以后可能加字段,坏条目跳过就好,别让一条脏数据把整张表清空
    return parsed.filter(
      (item): item is FilterRule =>
        typeof item === 'object' &&
        item !== null &&
        typeof (item as FilterRule).id === 'string' &&
        typeof (item as FilterRule).value === 'string' &&
        ['user', 'keyword', 'category'].includes((item as FilterRule).kind),
    );
  } catch {
    return [];
  }
}

export const useLocalFilters = create<LocalFilterState>()((set, get) => {
  const persist = (rules: readonly FilterRule[]) => {
    set({ rules });
    storage.set(RULES_KEY, JSON.stringify(rules));
  };
  return {
    rules: loadRules(),
    add: (input) => {
      const rule = createFilterRule(input, Math.floor(Date.now() / 1000));
      persist(upsertFilterRule(get().rules, rule));
      return rule;
    },
    remove: (id) => persist(removeFilterRule(get().rules, id)),
    // 「撤销」用:原样放回去(id 不变,所以不会和自己重复)
    restore: (rule) => persist(upsertFilterRule(get().rules, rule)),
  };
});

/** 官方屏蔽表按账号分桶:它是账号级云端数据,切号后不能拿上一个号的表充数。 */
export const blockWordsKey = (uid: string | null) => ['block-words', uid ?? 'guest'] as const;

/** 云端官方屏蔽表。游客不发请求(接口要登录),data 一直是 undefined。 */
export function useBlockWords(): UseQueryResult<BlockWordList> {
  const uid = useAccounts((state) => state.currentUid);
  return useQuery({
    queryKey: blockWordsKey(uid),
    queryFn: ({ signal }) =>
      fetchBlockWords(fetchNga, { uid: uid ?? '', ...(signal === undefined ? {} : { signal }) }),
    enabled: uid !== null,
    // 这份表现在是**每个主题列表页与详情页都要读**的东西(过滤要用它),
    // 所以缓存要压得住:5 分钟内不重问,并且订阅者归零后也留在内存里,
    // 免得来回进出版块时反复打 ucp(ADR-0002:能少打就少打)。改动都走 invalidate。
    staleTime: 5 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });
}

export interface BlockWordMutations {
  /** 加一个官方关键词 */
  addWord: (word: string) => Promise<void>;
  /** 删一个官方关键词 */
  removeWord: (word: string) => Promise<void>;
  /** 解除一个官方用户屏蔽 */
  removeUser: (user: BlockedUser) => Promise<void>;
  /** 整表写回。接口本来就是整表覆盖,「撤销」把改动前那张表原样写回去即可 */
  replace: (list: BlockWordList) => Promise<void>;
}

/**
 * 官方屏蔽表的写操作。
 *
 * 接口只有「整表覆盖」(API 文档 §11.5),所以每次写都是拿当前这张表改一条再写回去;
 * 表还没拉回来时不许写——那会拿一张空表覆盖掉云端的全部屏蔽词。
 */
export function useBlockWordMutations(): BlockWordMutations {
  const uid = useAccounts((state) => state.currentUid);
  const client = useQueryClient();
  const key = blockWordsKey(uid);

  const write = useMutation({
    mutationFn: (list: BlockWordList) => {
      if (uid === null) throw new Error('登录后才能同步官方屏蔽词');
      return setBlockWords(fetchNga, { uid, list });
    },
    onMutate: (list) => {
      const previous = client.getQueryData<BlockWordList>(key);
      client.setQueryData<BlockWordList>(key, list);
      return { previous };
    },
    onError: (_error, _list, context) => {
      if (context?.previous !== undefined) client.setQueryData(key, context.previous);
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  });

  const edit = async (change: (list: BlockWordList) => BlockWordList) => {
    const current = client.getQueryData<BlockWordList>(key);
    if (current === undefined) {
      // 整表覆盖的接口 + 没读到的表 = 一次静默清空,宁可报错让用户重进页面
      throw new Error('官方屏蔽表还没读到,下拉刷新后再试');
    }
    await write.mutateAsync(change(current));
  };

  return {
    addWord: (word) =>
      edit((list) => ({ ...list, words: [word, ...list.words.filter((item) => item !== word)] })),
    removeWord: (word) =>
      edit((list) => ({ ...list, words: list.words.filter((item) => item !== word) })),
    removeUser: (user) =>
      edit((list) => ({
        ...list,
        users: list.users.filter((item) =>
          user.uid === undefined ? item.name !== user.name : item.uid !== user.uid,
        ),
      })),
    replace: (list) => write.mutateAsync(list).then(() => undefined),
  };
}

/**
 * 参与判定的完整规则表:本地在前、官方在后。
 *
 * 官方那份没拉回来(游客 / 还在飞)时就只有本地规则,不阻塞列表渲染——
 * 云端表回来后 Query 会让订阅方重渲一次,那时命中的行再藏起来。
 */
export function useFilterRules(): readonly FilterRule[] {
  const local = useLocalFilters((state) => state.rules);
  const { data } = useBlockWords();
  return useMemo(
    () => [...local, ...officialFilterRules(data ?? EMPTY_BLOCK_WORDS)],
    [local, data],
  );
}

/**
 * 主题列表的过滤器:命中就隐藏那一行(票面:列表命中直接隐藏)。
 * 返回过滤后的数组;一条规则都没有时返回原数组本身,不白造一个新引用。
 */
export function useTopicFilter(): (topics: readonly Topic[]) => readonly Topic[] {
  const rules = useFilterRules();
  return useCallback(
    (topics) => {
      if (rules.length === 0) return topics;
      return topics.filter(
        (topic) =>
          matchFilterRules(rules, {
            title: topic.subject,
            author: topic.author,
            ...(topic.authorId === undefined ? {} : { authorId: topic.authorId }),
          }) === undefined,
      );
    },
    [rules],
  );
}

/**
 * 楼层的过滤器:命中不隐藏,折叠成一行灰字(票面),所以返回命中的规则本身,
 * 让详情页写得出「已屏蔽 xxx 的楼层」并提供「展开」。
 */
export function useFloorFilter(): (
  floor: Floor,
  user: FloorUser | undefined,
) => FilterRule | undefined {
  const rules = useFilterRules();
  return useCallback(
    (floor, user) => {
      if (rules.length === 0) return undefined;
      return matchFilterRules(rules, {
        content: floor.content,
        ...(floor.subject === undefined ? {} : { title: floor.subject }),
        ...(user === undefined ? {} : { author: user.name }),
        ...(user?.uid === undefined ? {} : { authorId: user.uid }),
      });
    },
    [rules],
  );
}

/** 楼层菜单「屏蔽此人」:加一条本地用户规则,返回它好让调用方给「撤销」。 */
export function blockUserLocally(name: string, uid: number | undefined): FilterRule {
  return useLocalFilters.getState().add({
    kind: 'user',
    value: name,
    ...(uid === undefined ? {} : { uid }),
  });
}
