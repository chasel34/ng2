import { create } from 'zustand';

import {
  nextSubBoardState,
  setSubBoardOption,
  subBoardState,
  type SubBoard,
  type SubBoardAction,
  type SubBoardState,
} from '@/core/api';

import { fetchNga } from './nga-client';

/**
 * 子版块订阅/屏蔽(CONTEXT.md「子版块」)的设备侧状态。
 *
 * 服务端**不回新的 attributes**,只回一句「操作成功」;而 attributes 是随主题列表
 * (`thread.php` 的 `__F.sub_forums`)一起下来的,重拉一次列表只为看一个开关太贵
 * (ADR-0002)。所以改过的状态记在这儿,盖在解析出来的 attributes 上:
 * 页面显示 = 本地改动 ?? 魔法数判定。下次重进版块拉到新列表时,自然回到服务端口径。
 *
 * 按账号分桶:切号后订阅关系不是同一份。
 */
const overrideKey = (uid: string, subBoard: SubBoard) => `${uid}:${subBoard.filterId}`;

interface SubBoardStore {
  /** 本地改过的订阅状态 */
  overrides: Readonly<Record<string, boolean>>;
  /** 在途的操作,挡住同一个子版块的重复点击 */
  pending: readonly string[];
  toggle: (options: ToggleSubBoardOptions) => Promise<void>;
}

export interface ToggleSubBoardOptions {
  readonly uid: string;
  readonly subBoard: SubBoard;
  /** 父版块 fid(子版块列表是从哪个版块来的) */
  readonly parentFid: number;
  readonly action: SubBoardAction;
}

const useSubBoardStore = create<SubBoardStore>()((set, get) => ({
  overrides: {},
  pending: [],

  toggle: async (options) => {
    const { uid, subBoard, parentFid, action } = options;
    const key = overrideKey(uid, subBoard);
    if (get().pending.includes(key)) return;

    const previous = get().overrides[key];
    // 乐观切换:开关点了就该立刻动,失败再回滚并把服务端的话交给调用方去说
    set((state) => ({
      overrides: { ...state.overrides, [key]: action === 'subscribe' },
      pending: [...state.pending, key],
    }));
    try {
      await setSubBoardOption(fetchNga, { subBoard, parentFid, action });
    } catch (error) {
      set((state) => {
        const overrides = { ...state.overrides };
        if (previous === undefined) delete overrides[key];
        else overrides[key] = previous;
        return { overrides };
      });
      throw error;
    } finally {
      set((state) => ({ pending: state.pending.filter((item) => item !== key) }));
    }
  },
}));

/** 一个子版块此刻该显示的状态。游客态没有本地改动,一律按 attributes 显示。 */
export function useSubBoardState(uid: string | null, subBoard: SubBoard): SubBoardState {
  const override = useSubBoardStore((state) =>
    uid === null ? undefined : state.overrides[overrideKey(uid, subBoard)],
  );
  const parsed = subBoardState(subBoard.attributes);
  return override === undefined
    ? parsed
    : nextSubBoardState(parsed, override ? 'subscribe' : 'block');
}

/** 这个子版块有没有操作在途(按钮转圈/禁用)。 */
export function useSubBoardPending(uid: string | null, subBoard: SubBoard): boolean {
  return useSubBoardStore((state) =>
    uid === null ? false : state.pending.includes(overrideKey(uid, subBoard)),
  );
}

export function useToggleSubBoard(): (options: ToggleSubBoardOptions) => Promise<void> {
  return useSubBoardStore((state) => state.toggle);
}
